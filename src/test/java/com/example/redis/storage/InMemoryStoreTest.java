package com.example.redis.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    // ---- LIST ----

    @Test
    void rpushAppendsInOrderAndReturnsLength() {
        assertEquals(3, store.rpush("mylist", "a", "b", "c"));
        assertEquals(java.util.List.of("a", "b", "c"), store.lrange("mylist", 0, -1));
    }

    @Test
    void lpushPrependsReversingMultiValueOrder() {
        // Matches real Redis: LPUSH k a b c leaves the list as [c, b, a].
        store.lpush("mylist", "a", "b", "c");
        assertEquals(java.util.List.of("c", "b", "a"), store.lrange("mylist", 0, -1));
    }

    @Test
    void lpopAndRpopRemoveFromRespectiveEnds() {
        store.rpush("mylist", "a", "b", "c");
        assertEquals("a", store.lpop("mylist"));
        assertEquals("c", store.rpop("mylist"));
        assertEquals(java.util.List.of("b"), store.lrange("mylist", 0, -1));
    }

    @Test
    void poppingLastElementDeletesTheKey() {
        store.rpush("mylist", "only");
        store.lpop("mylist");
        assertFalse(store.exists("mylist"));
    }

    @Test
    void lpopOnMissingListReturnsNull() {
        assertNull(store.lpop("missing"));
    }

    @Test
    void lrangeSupportsNegativeIndices() {
        store.rpush("mylist", "a", "b", "c", "d");
        assertEquals(java.util.List.of("c", "d"), store.lrange("mylist", -2, -1));
    }

    @Test
    void listCommandOnStringKeyThrowsWrongType() {
        store.set("name", "Yash");
        assertThrows(com.example.redis.exception.WrongTypeException.class,
                () -> store.rpush("name", "value"));
    }

    // ---- SET ----

    @Test
    void saddReturnsCountOfNewlyAddedMembers() {
        assertEquals(2, store.sadd("tags", "a", "b"));
        assertEquals(1, store.sadd("tags", "b", "c")); // "b" already present
    }

    @Test
    void sismemberReflectsMembership() {
        store.sadd("tags", "a");
        assertTrue(store.sismember("tags", "a"));
        assertFalse(store.sismember("tags", "z"));
        assertFalse(store.sismember("missing", "a"));
    }

    @Test
    void sremRemovesMembersAndDeletesEmptySet() {
        store.sadd("tags", "a");
        assertEquals(1, store.srem("tags", "a"));
        assertFalse(store.exists("tags"));
    }

    @Test
    void smembersReturnsSnapshotCopy() {
        store.sadd("tags", "a", "b");
        assertEquals(Set.of("a", "b"), store.smembers("tags"));
    }

    // ---- HASH ----

    @Test
    void hsetReturnsWhetherFieldWasNew() {
        assertTrue(store.hset("user:1", "name", "Yash"));
        assertFalse(store.hset("user:1", "name", "Sharma")); // overwrite, not new
    }

    @Test
    void hgetReturnsFieldValue() {
        store.hset("user:1", "name", "Yash");
        assertEquals("Yash", store.hget("user:1", "name"));
        assertNull(store.hget("user:1", "missing-field"));
    }

    @Test
    void hdelRemovesFieldAndDeletesEmptyHash() {
        store.hset("user:1", "name", "Yash");
        assertTrue(store.hdel("user:1", "name"));
        assertFalse(store.exists("user:1"));
    }

    @Test
    void hgetallReturnsAllFields() {
        store.hset("user:1", "name", "Yash");
        store.hset("user:1", "city", "Delhi");
        assertEquals(Map.of("name", "Yash", "city", "Delhi"), store.hgetall("user:1"));
    }

    // ---- SORTED SET (ZSET) ----

    @Test
    void zaddReturnsTrueForNewMemberFalseForScoreUpdate() {
        assertTrue(store.zadd("leaderboard", 10, "alice"));
        assertFalse(store.zadd("leaderboard", 20, "alice"));
    }

    @Test
    void zrangeReturnsMembersInAscendingScoreOrder() {
        store.zadd("leaderboard", 30, "charlie");
        store.zadd("leaderboard", 10, "alice");
        store.zadd("leaderboard", 20, "bob");
        assertEquals(java.util.List.of("alice", "bob", "charlie"), store.zrange("leaderboard", 0, -1));
    }

    @Test
    void zaddMovesMemberWhenScoreUpdated() {
        store.zadd("leaderboard", 10, "alice");
        store.zadd("leaderboard", 5, "bob");
        store.zadd("leaderboard", 100, "alice"); // alice should now rank last
        assertEquals(java.util.List.of("bob", "alice"), store.zrange("leaderboard", 0, -1));
    }

    @Test
    void zremRemovesMemberAndDeletesEmptyZset() {
        store.zadd("leaderboard", 10, "alice");
        assertTrue(store.zrem("leaderboard", "alice"));
        assertFalse(store.exists("leaderboard"));
    }

    @Test
    void zsetCommandOnListKeyThrowsWrongType() {
        store.rpush("mylist", "a");
        assertThrows(com.example.redis.exception.WrongTypeException.class,
                () -> store.zadd("mylist", 1, "member"));
    }
}
