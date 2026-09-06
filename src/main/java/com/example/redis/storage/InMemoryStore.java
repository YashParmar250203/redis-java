package com.example.redis.storage;

import com.example.redis.exception.WrongTypeException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Supplier;

/**
 * Thread-safe in-memory store backing all Redis data types (String, List,
 * Set, Hash, ZSet) plus TTL support.
 * <p>
 * Design notes:
 * <ul>
 *     <li><b>One keyspace, typed values.</b> Every entry carries a
 *     {@link RedisType} tag. Calling a list command against a key holding a
 *     string (or vice versa) throws {@link WrongTypeException}, matching
 *     real Redis.</li>
 *     <li><b>Per-type concurrent structures, not "lock + plain collection".</b>
 *     Lists use {@link ConcurrentLinkedDeque} (lock-free, O(1) push/pop from
 *     either end). Sets and hash field-maps use ConcurrentHashMap-backed
 *     structures. Sorted sets use {@link SortedSetValue}, which mirrors real
 *     Redis's own dual hash-table + ordered-structure design.</li>
 *     <li><b>Create-or-check-type is atomic.</b> {@link #getOrCreate} uses
 *     {@code ConcurrentHashMap.compute} so "does this key exist, is it the
 *     right type, if not create it" happens as one atomic step per key -
 *     the same pattern used for TTL updates in Phase 2.</li>
 *     <li><b>Empty containers are deleted.</b> Popping the last element of a
 *     list (or removing the last member of a set/hash/zset) removes the key
 *     entirely, matching real Redis.</li>
 * </ul>
 */
@Component
public class InMemoryStore implements Store, ListOperations, SetOperations, HashOperations, SortedSetOperations, Snapshottable {

    private static final int ACTIVE_EXPIRATION_SAMPLE_SIZE = 20;
    private static final int ACTIVE_EXPIRATION_MAX_ROUNDS = 5;
    private static final double ACTIVE_EXPIRATION_REPEAT_THRESHOLD = 0.25;

    private final ConcurrentHashMap<String, StoredValue> data = new ConcurrentHashMap<>();

    /** Index of keys currently carrying a TTL - see Phase 2 notes on active expiration. */
    private final Set<String> keysWithExpiry = ConcurrentHashMap.newKeySet();

    // ================= STRING + TTL =================

    @Override
    public void set(String key, String value) {
        data.put(key, new StoredValue(value, null, RedisType.STRING));
        keysWithExpiry.remove(key);
    }

    @Override
    public void set(String key, String value, long ttlSeconds) {
        long expireAt = System.currentTimeMillis() + ttlSeconds * 1000;
        data.put(key, new StoredValue(value, expireAt, RedisType.STRING));
        keysWithExpiry.add(key);
    }

    @Override
    public String get(String key) {
        StoredValue current = liveValueOrNull(key);
        if (current == null) {
            return null;
        }
        requireType(current, RedisType.STRING);
        return (String) current.value();
    }

    @Override
    public boolean delete(String key) {
        StoredValue removed = data.remove(key);
        keysWithExpiry.remove(key);
        return removed != null && !isExpired(removed);
    }

    @Override
    public boolean exists(String key) {
        return liveValueOrNull(key) != null;
    }

    @Override
    public boolean expire(String key, long ttlSeconds) {
        long newExpireAt = System.currentTimeMillis() + ttlSeconds * 1000;
        StoredValue updated = data.computeIfPresent(key,
                (k, existing) -> isExpired(existing) ? null
                        : new StoredValue(existing.value(), newExpireAt, existing.type()));
        if (updated == null) {
            keysWithExpiry.remove(key);
            return false;
        }
        keysWithExpiry.add(key);
        return true;
    }

    @Override
    public long ttl(String key) {
        StoredValue current = data.get(key);
        if (current == null) {
            return -2;
        }
        if (isExpired(current)) {
            reap(key, current);
            return -2;
        }
        if (current.expireAtMillis() == null) {
            return -1;
        }
        long remainingMillis = current.expireAtMillis() - System.currentTimeMillis();
        return Math.max(0, (remainingMillis + 999) / 1000);
    }

    @Override
    public int size() {
        return data.size();
    }

    @Override
    public int runActiveExpirationCycle() {
        int totalRemoved = 0;
        for (int round = 0; round < ACTIVE_EXPIRATION_MAX_ROUNDS; round++) {
            List<String> snapshot = new ArrayList<>(keysWithExpiry);
            if (snapshot.isEmpty()) {
                break;
            }
            Collections.shuffle(snapshot);
            int sampleSize = Math.min(ACTIVE_EXPIRATION_SAMPLE_SIZE, snapshot.size());
            int expiredThisRound = 0;
            for (int i = 0; i < sampleSize; i++) {
                String key = snapshot.get(i);
                StoredValue current = data.get(key);
                if (current == null) {
                    keysWithExpiry.remove(key);
                    continue;
                }
                if (isExpired(current)) {
                    reap(key, current);
                    expiredThisRound++;
                    totalRemoved++;
                }
            }
            if (expiredThisRound < sampleSize * ACTIVE_EXPIRATION_REPEAT_THRESHOLD) {
                break;
            }
        }
        return totalRemoved;
    }

    // ================= LIST =================

    @SuppressWarnings("unchecked")
    @Override
    public long lpush(String key, String... values) {
        StoredValue stored = getOrCreate(key, RedisType.LIST, ConcurrentLinkedDeque::new);
        ConcurrentLinkedDeque<String> list = (ConcurrentLinkedDeque<String>) stored.value();
        for (String value : values) {
            list.addFirst(value);
        }
        return list.size();
    }

    @SuppressWarnings("unchecked")
    @Override
    public long rpush(String key, String... values) {
        StoredValue stored = getOrCreate(key, RedisType.LIST, ConcurrentLinkedDeque::new);
        ConcurrentLinkedDeque<String> list = (ConcurrentLinkedDeque<String>) stored.value();
        for (String value : values) {
            list.addLast(value);
        }
        return list.size();
    }

    @Override
    public String lpop(String key) {
        return popFromList(key, true);
    }

    @Override
    public String rpop(String key) {
        return popFromList(key, false);
    }

    @SuppressWarnings("unchecked")
    private String popFromList(String key, boolean fromHead) {
        Optional<StoredValue> maybe = liveTypedOrEmpty(key, RedisType.LIST);
        if (maybe.isEmpty()) {
            return null;
        }
        StoredValue stored = maybe.get();
        ConcurrentLinkedDeque<String> list = (ConcurrentLinkedDeque<String>) stored.value();
        String popped = fromHead ? list.pollFirst() : list.pollLast();
        removeKeyIfContainerEmpty(key, stored, list.isEmpty());
        return popped;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<String> lrange(String key, int start, int stop) {
        Optional<StoredValue> maybe = liveTypedOrEmpty(key, RedisType.LIST);
        if (maybe.isEmpty()) {
            return List.of();
        }
        ConcurrentLinkedDeque<String> list = (ConcurrentLinkedDeque<String>) maybe.get().value();
        List<String> snapshot = new ArrayList<>(list);
        int[] range = RangeUtils.resolve(start, stop, snapshot.size());
        if (range[0] > range[1]) {
            return List.of();
        }
        return new ArrayList<>(snapshot.subList(range[0], range[1] + 1));
    }

    // ================= SET =================

    @SuppressWarnings("unchecked")
    @Override
    public long sadd(String key, String... values) {
        StoredValue stored = getOrCreate(key, RedisType.SET, () -> ConcurrentHashMap.newKeySet());
        Set<String> set = (Set<String>) stored.value();
        long added = 0;
        for (String value : values) {
            if (set.add(value)) {
                added++;
            }
        }
        return added;
    }

    @SuppressWarnings("unchecked")
    @Override
    public long srem(String key, String... values) {
        Optional<StoredValue> maybe = liveTypedOrEmpty(key, RedisType.SET);
        if (maybe.isEmpty()) {
            return 0;
        }
        StoredValue stored = maybe.get();
        Set<String> set = (Set<String>) stored.value();
        long removed = 0;
        for (String value : values) {
            if (set.remove(value)) {
                removed++;
            }
        }
        removeKeyIfContainerEmpty(key, stored, set.isEmpty());
        return removed;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean sismember(String key, String value) {
        Optional<StoredValue> maybe = liveTypedOrEmpty(key, RedisType.SET);
        return maybe.map(sv -> ((Set<String>) sv.value()).contains(value)).orElse(false);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Set<String> smembers(String key) {
        Optional<StoredValue> maybe = liveTypedOrEmpty(key, RedisType.SET);
        return maybe.map(sv -> new LinkedHashSet<>((Set<String>) sv.value()))
                .map(copy -> (Set<String>) copy)
                .orElseGet(LinkedHashSet::new);
    }

    // ================= HASH =================

    @SuppressWarnings("unchecked")
    @Override
    public boolean hset(String key, String field, String value) {
        StoredValue stored = getOrCreate(key, RedisType.HASH, ConcurrentHashMap::new);
        Map<String, String> hash = (Map<String, String>) stored.value();
        return hash.put(field, value) == null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public String hget(String key, String field) {
        Optional<StoredValue> maybe = liveTypedOrEmpty(key, RedisType.HASH);
        return maybe.map(sv -> ((Map<String, String>) sv.value()).get(field)).orElse(null);
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean hdel(String key, String field) {
        Optional<StoredValue> maybe = liveTypedOrEmpty(key, RedisType.HASH);
        if (maybe.isEmpty()) {
            return false;
        }
        StoredValue stored = maybe.get();
        Map<String, String> hash = (Map<String, String>) stored.value();
        boolean removed = hash.remove(field) != null;
        removeKeyIfContainerEmpty(key, stored, hash.isEmpty());
        return removed;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, String> hgetall(String key) {
        Optional<StoredValue> maybe = liveTypedOrEmpty(key, RedisType.HASH);
        return maybe.map(sv -> new LinkedHashMap<>((Map<String, String>) sv.value()))
                .map(copy -> (Map<String, String>) copy)
                .orElseGet(LinkedHashMap::new);
    }

    // ================= SORTED SET (ZSET) =================

    @Override
    public boolean zadd(String key, double score, String member) {
        StoredValue stored = getOrCreate(key, RedisType.ZSET, SortedSetValue::new);
        SortedSetValue zset = (SortedSetValue) stored.value();
        return zset.add(member, score);
    }

    @Override
    public boolean zrem(String key, String member) {
        Optional<StoredValue> maybe = liveTypedOrEmpty(key, RedisType.ZSET);
        if (maybe.isEmpty()) {
            return false;
        }
        StoredValue stored = maybe.get();
        SortedSetValue zset = (SortedSetValue) stored.value();
        boolean removed = zset.remove(member);
        removeKeyIfContainerEmpty(key, stored, zset.size() == 0);
        return removed;
    }

    @Override
    public List<String> zrange(String key, int start, int stop) {
        Optional<StoredValue> maybe = liveTypedOrEmpty(key, RedisType.ZSET);
        if (maybe.isEmpty()) {
            return List.of();
        }
        SortedSetValue zset = (SortedSetValue) maybe.get().value();
        List<String> membersInOrder = zset.membersInOrder();
        int[] range = RangeUtils.resolve(start, stop, membersInOrder.size());
        if (range[0] > range[1]) {
            return List.of();
        }
        return new ArrayList<>(membersInOrder.subList(range[0], range[1] + 1));
    }

    // ================= SNAPSHOT (RDB-style) =================

    /**
     * Serializes a copy of the current keyspace via Java serialization.
     * <p>
     * Copying into a plain {@link HashMap} first (rather than serializing
     * the live ConcurrentHashMap directly) gives a faster, "mostly
     * consistent" point-in-time view - not a strictly atomic one. Real
     * Redis's RDB save forks the process to get a true copy-on-write
     * snapshot without blocking writers at all; reproducing that here would
     * mean shelling out to the OS fork() call, which the JVM doesn't expose
     * directly. Documented trade-off, not an oversight.
     * <p>
     * Java serialization itself is also a stated simplification: it's not
     * cross-language portable and can break across incompatible code
     * changes, unlike Redis's own compact, versioned, custom binary RDB
     * format. A production system would want a stable schema (e.g.
     * Protocol Buffers or a hand-rolled versioned binary format) instead.
     */
    @Override
    public byte[] createSnapshot() throws IOException {
        Map<String, StoredValue> copy = new HashMap<>(data);
        try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
             ObjectOutputStream objectStream = new ObjectOutputStream(byteStream)) {
            objectStream.writeObject(copy);
            return byteStream.toByteArray();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restoreSnapshot(byte[] snapshotData) throws IOException {
        Map<String, StoredValue> restored;
        try (ByteArrayInputStream byteStream = new ByteArrayInputStream(snapshotData);
             ObjectInputStream objectStream = new ObjectInputStream(byteStream)) {
            restored = (Map<String, StoredValue>) objectStream.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Corrupt snapshot: unrecognized class in stream", e);
        }

        data.clear();
        keysWithExpiry.clear();
        data.putAll(restored);
        for (Map.Entry<String, StoredValue> entry : restored.entrySet()) {
            if (entry.getValue().expireAtMillis() != null) {
                keysWithExpiry.add(entry.getKey());
            }
        }
    }

    // ================= shared internals =================

    /**
     * Atomically fetches the value for {@code key} if present and of the
     * expected type, or creates a fresh empty container of that type if the
     * key is absent or logically expired. Throws WrongTypeException if the
     * key holds a different type. Backs every "mutate, creating if needed"
     * command (LPUSH, SADD, HSET, ZADD).
     */
    private StoredValue getOrCreate(String key, RedisType expectedType, Supplier<Object> emptyValueSupplier) {
        return data.compute(key, (k, existing) -> {
            if (existing == null || isExpired(existing)) {
                keysWithExpiry.remove(key);
                return new StoredValue(emptyValueSupplier.get(), null, expectedType);
            }
            if (existing.type() != expectedType) {
                throw new WrongTypeException();
            }
            return existing;
        });
    }

    /**
     * Read path for type-specific commands: empty if the key is
     * absent/expired (callers treat that as "empty collection", not an
     * error - matching Redis), or throws WrongTypeException if present but
     * the wrong type.
     */
    private Optional<StoredValue> liveTypedOrEmpty(String key, RedisType expectedType) {
        StoredValue current = liveValueOrNull(key);
        if (current == null) {
            return Optional.empty();
        }
        requireType(current, expectedType);
        return Optional.of(current);
    }

    private void requireType(StoredValue value, RedisType expectedType) {
        if (value.type() != expectedType) {
            throw new WrongTypeException();
        }
    }

    private StoredValue liveValueOrNull(String key) {
        StoredValue current = data.get(key);
        if (current == null) {
            return null;
        }
        if (isExpired(current)) {
            reap(key, current);
            return null;
        }
        return current;
    }

    /**
     * Matches real Redis's behaviour of deleting a key entirely once its
     * collection becomes empty, e.g. RPOP-ing the last element of a list
     * removes the list key rather than leaving an empty one behind.
     */
    private void removeKeyIfContainerEmpty(String key, StoredValue expectedValue, boolean isEmpty) {
        if (isEmpty) {
            reap(key, expectedValue);
        }
    }

    private void reap(String key, StoredValue expectedValue) {
        data.remove(key, expectedValue);
        keysWithExpiry.remove(key);
    }

    private boolean isExpired(StoredValue value) {
        return value.expireAtMillis() != null && value.expireAtMillis() <= System.currentTimeMillis();
    }
}
