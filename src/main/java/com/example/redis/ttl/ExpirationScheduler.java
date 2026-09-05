package com.example.redis.ttl;

import com.example.redis.storage.Store;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives active expiration independent of the storage engine's own logic.
 * <p>
 * Kept as a separate component (rather than a self-scheduling thread inside
 * InMemoryStore) so the storage engine only knows *how* to expire keys, not
 * *when* to - the scheduling policy (fixed delay, eventually maybe adaptive
 * based on load) can change without touching storage code.
 * <p>
 * 100ms matches real Redis's default active-expire-cycle frequency (10/sec).
 */
@Component
public class ExpirationScheduler {

    private final Store store;

    public ExpirationScheduler(Store store) {
        this.store = store;
    }

    @Scheduled(fixedDelay = 100)
    public void sweepExpiredKeys() {
        store.runActiveExpirationCycle();
    }
}
