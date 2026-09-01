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