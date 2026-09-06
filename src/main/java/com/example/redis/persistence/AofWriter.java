package com.example.redis.persistence;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Appends every successfully-executed write command to the append-only file
 * (AOF), in RESP multibulk format - the same wire format real Redis itself
 * has used for its AOF since Redis 7. Storing entries as RESP means replay
 * ({@link AofReader}) can reuse the exact same incremental parser built for
 * the TCP server in Phase 4, rather than a second parallel format.
 * <p>
 * Only active when {@code redis.persistence.mode=aof}.
 * <p>
 * All channel operations are synchronized on this instance. FileChannel's
 * append-mode write is not documented as safe for unsynchronized concurrent
 * callers, and this server can call it from multiple threads at once (the
 * TCP event-loop thread and Tomcat's REST request threads both funnel
 * through the same decorator) - synchronizing is the simple, obviously
 * correct choice, at some throughput cost under heavy concurrent writes.
 */
@Component
@ConditionalOnProperty(name = "redis.persistence.mode", havingValue = "aof")
public class AofWriter {

    private final Path filePath;
    private final AofFsyncPolicy fsyncPolicy;
    private final AtomicBoolean dirtySinceLastFsync = new AtomicBoolean(false);

    private FileChannel channel;

    public AofWriter(@Value("${redis.aof.path:data/appendonly.aof}") String path,
                      @Value("${redis.aof.fsync:everysec}") String fsyncPolicy) {
        this.filePath = Paths.get(path);
        this.fsyncPolicy = AofFsyncPolicy.fromConfig(fsyncPolicy);
    }

    @PostConstruct
    public void open() throws IOException {
        Path parent = filePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        channel = FileChannel.open(filePath, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    public synchronized void append(String respEncodedCommand) throws IOException {
        channel.write(ByteBuffer.wrap(respEncodedCommand.getBytes(StandardCharsets.UTF_8)));
        dirtySinceLastFsync.set(true);
        if (fsyncPolicy == AofFsyncPolicy.ALWAYS) {
            channel.force(false);
            dirtySinceLastFsync.set(false);
        }
    }

    /** The actual mechanism behind EVERYSEC: runs once a second regardless of write volume. */
    @Scheduled(fixedRate = 1000)
    public synchronized void fsyncIfDirty() throws IOException {
        if (fsyncPolicy == AofFsyncPolicy.EVERYSEC && dirtySinceLastFsync.compareAndSet(true, false)) {
            channel.force(false);
        }
    }

    @PreDestroy
    public synchronized void close() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.force(false); // best-effort final flush on graceful shutdown, regardless of policy
            channel.close();
        }
    }
}
