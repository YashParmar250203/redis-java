package com.example.redis.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryStoreTest {

    private InMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
    }

    @Test
    void setThenGetReturnsStoredValue() {
        store.set("name", "Yash");
        assertEquals("Yash", store.get("name"));
    }

    @Test
    void getOnMissingKeyReturnsNull() {
        assertNull(store.get("does-not-exist"));
    }

    @Test
    void setOverwritesExistingValue() {
        store.set("name", "Yash");
        store.set("name", "Sharma");
        assertEquals("Sharma", store.get("name"));
    }

    @Test
    void deleteRemovesExistingKeyAndReturnsTrue() {
        store.set("name", "Yash");
        assertTrue(store.delete("name"));
        assertNull(store.get("name"));
    }

    @Test
    void deleteOnMissingKeyReturnsFalse() {
        assertFalse(store.delete("does-not-exist"));
    }

    @Test
    void existsReflectsCurrentState() {
        assertFalse(store.exists("name"));
        store.set("name", "Yash");
        assertTrue(store.exists("name"));
        store.delete("name");
        assertFalse(store.exists("name"));
    }

    @Test
    void sizeTracksNumberOfKeys() {
        assertEquals(0, store.size());
        store.set("a", "1");
        store.set("b", "2");
        assertEquals(2, store.size());
        store.delete("a");
        assertEquals(1, store.size());
    }

    @Test
    void concurrentWritesToDifferentKeysAreAllPersisted() throws InterruptedException {
        int threadCount = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    store.set("key-" + index, "value-" + index);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(threadCount, store.size());
        for (int i = 0; i < threadCount; i++) {
            assertEquals("value-" + i, store.get("key-" + i));
        }
    }

    // ---- TTL / expiration ----

    @Test
    void keyExpiresLazilyAfterTtlElapses() throws InterruptedException {
        store.set("session", "abc", 1);
        assertEquals("abc", store.get("session"));
        Thread.sleep(1100);
        assertNull(store.get("session"));
    }

    @Test
    void existsReflectsLazyExpiration() throws InterruptedException {
        store.set("session", "abc", 1);
        assertTrue(store.exists("session"));
        Thread.sleep(1100);
        assertFalse(store.exists("session"));
    }

    @Test
    void ttlReturnsRemainingSecondsForKeyWithExpiry() {
        store.set("session", "abc", 60);
        long ttl = store.ttl("session");
        assertTrue(ttl > 0 && ttl <= 60);
    }

    @Test
    void ttlReturnsMinusOneForKeyWithoutExpiry() {
        store.set("name", "Yash");
        assertEquals(-1, store.ttl("name"));
    }

    @Test
    void ttlReturnsMinusTwoForMissingKey() {
        assertEquals(-2, store.ttl("missing"));
    }

    @Test
    void ttlReturnsMinusTwoForExpiredKey() throws InterruptedException {
        store.set("session", "abc", 1);
        Thread.sleep(1100);
        assertEquals(-2, store.ttl("session"));
    }

    @Test
    void expireSetsTtlOnExistingKeyWithoutOne() {
        store.set("name", "Yash");
        assertTrue(store.expire("name", 30));
        long ttl = store.ttl("name");
        assertTrue(ttl > 0 && ttl <= 30);
    }

    @Test
    void expireUpdatesTtlOnKeyThatAlreadyHasOne() {
        store.set("name", "Yash", 5);
        assertTrue(store.expire("name", 100));
        long ttl = store.ttl("name");
        assertTrue(ttl > 5 && ttl <= 100);
    }

    @Test
    void expireOnMissingKeyReturnsFalse() {
        assertFalse(store.expire("missing", 30));
    }

    @Test
    void expireOnAlreadyExpiredKeyReturnsFalse() throws InterruptedException {
        store.set("session", "abc", 1);
        Thread.sleep(1100);
        assertFalse(store.expire("session", 30));
    }

    @Test
    void plainSetClearsExistingTtl() {
        store.set("name", "Yash", 60);
        store.set("name", "Sharma");
        assertEquals(-1, store.ttl("name"));
    }

    @Test
    void deleteOnLogicallyExpiredKeyReturnsFalse() throws InterruptedException {
        store.set("temp", "a", 1);
        Thread.sleep(1100);
        assertFalse(store.delete("temp"));
    }

    @Test
    void activeExpirationCycleRemovesExpiredKeysWithoutBeingRead() throws InterruptedException {
        store.set("temp1", "a", 1);
        store.set("temp2", "b", 1);
        store.set("permanent", "c");
        Thread.sleep(1100);

        int removed = store.runActiveExpirationCycle();

        assertEquals(2, removed);
        assertEquals(1, store.size());
        assertTrue(store.exists("permanent"));
    }

    @Test
    void activeExpirationCycleIsNoOpWhenNothingHasExpired() {
        store.set("name", "Yash", 60);
        store.set("permanent", "value");

        int removed = store.runActiveExpirationCycle();

        assertEquals(0, removed);
        assertEquals(2, store.size());
    }

    @Test
    void concurrentIncrementStyleUpdatesToSameKeyDoNotCorruptState() throws InterruptedException {
        // Demonstrates that individual set/get calls are safe, while also
        // documenting that read-modify-write sequences (like INCR) are NOT
        // atomic through this interface alone - that's a Phase 6 concern
        // (compute()/atomic helper methods) once numeric commands exist.
        store.set("counter", "0");
        int threadCount = 20;
        AtomicInteger successfulWrites = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    store.set("counter", Thread.currentThread().getName());
                    successfulWrites.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(threadCount, successfulWrites.get());
        assertTrue(store.exists("counter"));
    }
}