package com.example.redis.server;

import com.example.redis.command.CommandDispatcher;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A single-threaded, non-blocking (NIO Selector-based) TCP server exposing
 * the same {@link CommandDispatcher} used by the REST layer, over raw
 * sockets speaking RESP (and Redis's inline-command shorthand).
 * <p>
 * <b>Why single-threaded, deliberately:</b> this mirrors real Redis's own
 * concurrency model. One event-loop thread handles accepting connections,
 * reading bytes, parsing, executing commands, and writing replies for every
 * connected client. There is no per-connection thread and no locking needed
 * around command execution on this path - a command runs to completion
 * before the loop looks at the next ready channel, so two clients' commands
 * can never interleave mid-execution. The trade-off (true of real Redis too)
 * is that one slow or CPU-heavy command blocks every other client until it
 * finishes. Benchmarking this against the REST path - which runs on
 * Spring's multi-threaded Tomcat pool - is a natural later comparison.
 * <p>
 * Started via {@link ApplicationRunner} so it comes up alongside the rest of
 * the Spring context without blocking application startup: binding happens
 * synchronously in {@link #run}, but the accept/read/write loop itself runs
 * on its own background thread. Ordered to run after persistence recovery
 * (see {@code persistence.PersistenceRecoveryRunner}'s @Order(1)) so no
 * client can connect before recovered data is in place.
 */
@Component
@Order(2)
public class TcpServer implements ApplicationRunner {

    private final CommandDispatcher commandDispatcher;
    private final int configuredPort;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private volatile int boundPort;

    public TcpServer(CommandDispatcher commandDispatcher, @Value("${redis.tcp.port:6380}") int configuredPort) {
        this.commandDispatcher = commandDispatcher;
        this.configuredPort = configuredPort;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(configuredPort));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        boundPort = serverChannel.socket().getLocalPort();
        running.set(true);

        Thread eventLoopThread = new Thread(this::eventLoop, "redis-tcp-event-loop");
        eventLoopThread.setDaemon(true);
        eventLoopThread.start();
    }

    /** Exposed mainly for tests, which bind to port 0 and need to know what port was actually assigned. */
    public int getBoundPort() {
        return boundPort;
    }

    private void eventLoop() {
        while (running.get()) {
            try {
                selector.select(); // blocks until at least one channel is ready
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();
                    handleKey(key);
                }
            } catch (ClosedSelectorException e) {
                break; // selector was closed during shutdown - exit the loop cleanly
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("TCP event loop error: " + e.getMessage());
                }
            }
        }
    }

    private void handleKey(SelectionKey key) {
        try {
            if (!key.isValid()) {
                return;
            }
            if (key.isAcceptable()) {
                acceptConnection();
            } else if (key.isReadable()) {
                ((ClientConnection) key.attachment()).handleReadable(key);
            }
        } catch (IOException e) {
            closeQuietly(key);
        }
    }

    private void acceptConnection() throws IOException {
        SocketChannel clientChannel = serverChannel.accept();
        if (clientChannel == null) {
            return;
        }
        clientChannel.configureBlocking(false);
        SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
        clientKey.attach(new ClientConnection(clientChannel, commandDispatcher));
    }

    private void closeQuietly(SelectionKey key) {
        try {
            key.channel().close();
        } catch (IOException ignored) {
            // Connection is going away anyway; nothing more to do.
        }
        key.cancel();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        try {
            if (selector != null) {
                selector.close();
            }
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
            // Best-effort shutdown.
        }
    }
}
