package com.example.redis.persistence;

import com.example.redis.command.CommandExecutor;
import com.example.redis.storage.Snapshottable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Restores state at startup according to {@code redis.persistence.mode},
 * before {@code server.TcpServer} (see its {@code @Order(2)}) starts
 * accepting connections.
 * <p>
 * Replay always goes through the raw {@link CommandExecutor}, never the
 * AOF-appending {@link PersistingCommandDispatcher} - otherwise every
 * restart would re-append everything it just replayed, growing the AOF
 * forever.
 * <p>
 * Known startup-ordering caveat: Spring Boot starts the embedded Tomcat
 * server as part of context refresh, which completes *before*
 * ApplicationRunners are invoked - meaning there is a brief window where
 * an incoming REST request could theoretically arrive before this recovery
 * finishes, even with @Order. A fully production-hardened version would
 * gate readiness (e.g. via Spring's ApplicationAvailability API) until
 * recovery completes; left as a Phase 10 hardening concern here, since REST
 * is only used for ad hoc testing in this project.
 */
@Component
@Order(1)
public class PersistenceRecoveryRunner implements ApplicationRunner {

    private final CommandExecutor rawCommandExecutor;
    private final Snapshottable snapshottable;
    private final String mode;
    private final Path aofPath;
    private final Path snapshotPath;

    public PersistenceRecoveryRunner(CommandExecutor rawCommandExecutor,
                                      Snapshottable snapshottable,
                                      @Value("${redis.persistence.mode:aof}") String mode,
                                      @Value("${redis.aof.path:data/appendonly.aof}") String aofPath,
                                      @Value("${redis.snapshot.path:data/dump.snapshot}") String snapshotPath) {
        this.rawCommandExecutor = rawCommandExecutor;
        this.snapshottable = snapshottable;
        this.mode = mode.trim().toLowerCase();
        this.aofPath = Paths.get(aofPath);
        this.snapshotPath = Paths.get(snapshotPath);
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        switch (mode) {
            case "aof" -> recoverFromAof();
            case "snapshot" -> recoverFromSnapshot();
            default -> System.out.println("Persistence mode '" + mode + "' - starting with an empty database.");
        }
    }

    private void recoverFromAof() throws IOException {
        AofReader.ReadResult result = AofReader.readAll(aofPath);

        for (String[] command : result.commands()) {
            try {
                rawCommandExecutor.execute(command);
            } catch (RuntimeException e) {
                // A command that was valid when originally logged should
                // always re-apply cleanly. If it doesn't, skip it rather
                // than aborting the whole recovery, but surface it loudly -
                // silently swallowing this would hide real bugs.
                System.err.println("Skipped AOF command during replay: " + e.getMessage());
            }
        }

        String summary = "AOF recovery: replayed " + result.commands().size() + " command(s) from " + aofPath;
        if (result.stoppedEarlyDueToCorruption()) {
            summary += " (stopped early - trailing data was incomplete or corrupted)";
        }
        System.out.println(summary + ".");
    }

    private void recoverFromSnapshot() throws IOException {
        if (!Files.exists(snapshotPath)) {
            System.out.println("No snapshot file found at " + snapshotPath + " - starting with an empty database.");
            return;
        }
        snapshottable.restoreSnapshot(Files.readAllBytes(snapshotPath));
        System.out.println("Snapshot recovery: restored state from " + snapshotPath);
    }
}
