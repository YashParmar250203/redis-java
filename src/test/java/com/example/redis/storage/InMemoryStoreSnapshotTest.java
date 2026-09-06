package com.example.redis.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryStoreSnapshotTest {

    @Test
    void snapshotRoundTripsAllDataTypesAndTtl() throws IOException {
        InMemoryStore original = new InMemoryStore();
        original.set("name", "Yash");
        original.set("session", "abc", 60);
        original.rpush("mylist", "a", "b", "c");
        original.sadd("tags", "x", "y");
        original.hset("user:1", "city", "Delhi");
        original.zadd("leaderboard", 10, "alice");
        original.zadd("leaderboard", 5, "bob");

        byte[] snapshot = original.createSnapshot();

        InMemoryStore restored = new InMemoryStore();
        restored.restoreSnapshot(snapshot);

        assertEquals("Yash", restored.get("name"));
        assertEquals("abc", restored.get("session"));
        assertTrue(restored.ttl("session") > 0 && restored.ttl("session") <= 60);
        assertEquals(List.of("a", "b", "c"), restored.lrange("mylist", 0, -1));
        assertEquals(Set.of("x", "y"), restored.smembers("tags"));
        assertEquals(Map.of("city", "Delhi"), restored.hgetall("user:1"));
        assertEquals(List.of("bob", "alice"), restored.zrange("leaderboard", 0, -1));
    }

    @Test
    void restoreCompletelyReplacesExistingState() throws IOException {
        InMemoryStore original = new InMemoryStore();
        original.set("keepme", "value");
        byte[] snapshot = original.createSnapshot();

        InMemoryStore restored = new InMemoryStore();
        restored.set("shouldBeGone", "value");
        restored.restoreSnapshot(snapshot);

        assertEquals("value", restored.get("keepme"));
        assertFalse(restored.exists("shouldBeGone"));
    }

    @Test
    void keyThatExpiredButWasNeverReapedIsStillTreatedAsExpiredAfterRestore() throws IOException, InterruptedException {
        InMemoryStore original = new InMemoryStore();
        original.set("temp", "value", 1);
        Thread.sleep(1100); // logically expired now, but nothing has read/reaped it yet

        // Snapshotting copies the live map as-is, expired-but-unreaped entries included.
        byte[] snapshot = original.createSnapshot();

        InMemoryStore restored = new InMemoryStore();
        restored.restoreSnapshot(snapshot);

        assertFalse(restored.exists("temp"));
    }

    @Test
    void emptyStoreSnapshotsAndRestoresCleanly() throws IOException {
        InMemoryStore original = new InMemoryStore();
        byte[] snapshot = original.createSnapshot();

        InMemoryStore restored = new InMemoryStore();
        restored.restoreSnapshot(snapshot);

        assertEquals(0, restored.size());
    }
}
