package com.example.redis.storage;

/**
 * Shared index resolution for LRANGE / ZRANGE: both support negative indices
 * (-1 = last element) and clip out-of-bounds indices rather than erroring,
 * matching real Redis range semantics.
 */
final class RangeUtils {

    private RangeUtils() {
    }

    /**
     * @return an inclusive [from, to] pair clipped to [0, size-1], or
     * {from=0, to=-1} (from > to) to signal an empty result.
     */
    static int[] resolve(int start, int stop, int size) {
        if (size == 0) {
            return new int[]{0, -1};
        }
        int from = normalize(start, size);
        int to = Math.min(normalize(stop, size), size - 1);
        if (from > to) {
            return new int[]{0, -1};
        }
        return new int[]{from, to};
    }

    private static int normalize(int index, int size) {
        return index < 0 ? Math.max(size + index, 0) : index;
    }
}
