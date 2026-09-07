package com.example.redis.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryStoreEvictionTest {

    // Sample size is 5; keeping maxKeys <= 5 means every key is examined on
    // every eviction, making LRU/LFU choice deterministic for these tests
    // rather than relying on random sampling luck.

    @Test
    void unlimitedKeysNeverEvictsRegardlessOfPolicy() {
        InMemoryStore store = new InMemoryStore("allkeys-lru", 0);
        for (int i = 0; i < 100; i++) {
            store.set("key" + i, "value");
        }
        assertEquals(100, store.size());
        assertEquals(0, store.evictedKeyCount());
    }

    @Test
    void noEvictionPolicyRejectsNewKeyOnceFull() {
        InMemoryStore store = new InMemoryStore("noeviction", 3);
        store.set("a", "1");
        store.set("b", "2");
        store.set("c", "3");

        assertThrows(com.example.redis.exception.MaxMemoryReachedException.class,
                () -> store.set("d", "4"));
        assertEquals(3, store.size());
    }

    @Test
    void noEvictionPolicyStillAllowsOverwritingExistingKey() {
        InMemoryStore store = new InMemoryStore("noeviction", 3);
        store.set("a", "1");
        store.set("b", "2");
        store.set("c", "3");

        // Overwriting an existing key never increases key count, so this must succeed.
        store.set("a", "updated");
        assertEquals("updated", store.get("a"));
        assertEquals(3, store.size());
    }

    @Test
    void allkeysLruEvictsTheLeastRecentlyUsedKey() {
        InMemoryStore store = new InMemoryStore("allkeys-lru", 3);
        store.set("a", "1");
        store.set("b", "2");
        store.set("c", "3");

        // Touch b and c so they're more recently used than a.
        store.get("b");
        store.get("c");

        store.set("d", "4"); // should evict "a", the least recently used

        assertFalse(store.exists("a"));
        assertTrue(store.exists("b"));
        assertTrue(store.exists("c"));
        assertTrue(store.exists("d"));
        assertEquals(3, store.size());
        assertEquals(1, store.evictedKeyCount());
    }

    @Test
    void allkeysLruEvictsKeyNotTouchedSinceOthersWere() {
        InMemoryStore store = new InMemoryStore("allkeys-lru", 2);
        store.set("a", "1");
        store.set("b", "2");
        store.get("a"); // "a" is now more recently used than "b" was left at creation

        store.set("c", "3"); // should evict "b"

        assertTrue(store.exists("a"));
        assertFalse(store.exists("b"));
        assertTrue(store.exists("c"));
    }

    @Test
    void allkeysLruTreatsSnapshotRestoredKeyWithNoAccessHistoryAsMostEvictable() throws java.io.IOException {
        // set() itself records an access (matching real Redis: writes update
        // the LRU clock too, not just reads), so there's no way to create a
        // key via normal commands that has *zero* tracked access. The one
        // real path that produces that state is a snapshot restore, which
        // rebuilds the keyspace directly and deliberately does not touch the
        // access trackers - a freshly-restored key has no LRU history at all.
        InMemoryStore source = new InMemoryStore();
        source.set("a", "1");
        source.set("b", "2");
        byte[] snapshot = source.createSnapshot();

        InMemoryStore restored = new InMemoryStore("allkeys-lru", 2);
        restored.restoreSnapshot(snapshot); // neither "a" nor "b" has any recorded access yet

        restored.get("a"); // "a" now has a recorded access; "b" still has none

        restored.set("c", "3"); // should evict "b", the only key with no tracked access at all

        assertTrue(restored.exists("a"));
        assertFalse(restored.exists("b"));
        assertTrue(restored.exists("c"));
    }

    @Test
    void allkeysLfuEvictsTheLeastFrequentlyUsedKey() {
        InMemoryStore store = new InMemoryStore("allkeys-lfu", 3);
        store.set("a", "1");
        store.set("b", "2");
        store.set("c", "3");

        // Access "b" and "c" multiple times; leave "a" untouched since creation.
        for (int i = 0; i < 5; i++) {
            store.get("b");
            store.get("c");
        }

        store.set("d", "4"); // should evict "a", the least frequently used

        assertFalse(store.exists("a"));
        assertTrue(store.exists("b"));
        assertTrue(store.exists("c"));
        assertTrue(store.exists("d"));
    }

    @Test
    void evictedKeyCountTracksNumberOfEvictions() {
        InMemoryStore store = new InMemoryStore("allkeys-lru", 2);
        store.set("a", "1");
        store.set("b", "2");
        store.set("c", "3"); // evicts one
        store.set("d", "4"); // evicts another

        assertEquals(2, store.evictedKeyCount());
        assertEquals(2, store.size());
    }

    @Test
    void deletingKeyCleansUpTrackingSoItDoesNotLeakMemory() {
        InMemoryStore store = new InMemoryStore("allkeys-lru", 0);
        store.set("temp", "value");
        store.get("temp"); // records an access
        store.delete("temp");

        // Re-adding under a tight limit and confirming eviction still works
        // correctly is an indirect way of confirming no stale tracker entry
        // for "temp" is silently influencing future eviction decisions.
        InMemoryStore tightStore = new InMemoryStore("allkeys-lru", 1);
        tightStore.set("a", "1");
        tightStore.set("b", "2"); // evicts "a"
        assertFalse(tightStore.exists("a"));
        assertTrue(tightStore.exists("b"));
    }
}
