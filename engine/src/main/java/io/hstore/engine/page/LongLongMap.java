// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

public final class LongLongMap {

    @FunctionalInterface
    public interface Visitor {
        void accept(long key, long value);
    }

    private static final int MINIMUM = 16;
    private static final int RETAINED = 1 << 20;

    private final int initial;
    private long[] keys;
    private long[] values;
    private int[] used;
    private int size;

    public LongLongMap(int expected) {
        this.initial = capacityFor(expected);
        this.keys = new long[initial];
        this.values = new long[initial];
        this.used = new int[initial];
    }

    private static int capacityFor(int entries) {
        return Math.max(MINIMUM, Integer.highestOneBit(Math.max(1, entries) * 2 - 1) << 1);
    }

    private int slot(long key) {
        long mixed = key * 0x9E3779B97F4A7C15L;
        return (int) (mixed ^ (mixed >>> 32)) & (keys.length - 1);
    }

    private int find(long key) {
        if (key == 0) {
            throw new IllegalArgumentException("zero is not a valid key");
        }
        int mask = keys.length - 1;
        int slot = slot(key);
        while (keys[slot] != 0 && keys[slot] != key) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    public long get(long key, long missing) {
        int slot = find(key);
        return keys[slot] == 0 ? missing : values[slot];
    }

    public boolean containsKey(long key) {
        return keys[find(key)] != 0;
    }

    public void put(long key, long value) {
        int slot = find(key);
        if (keys[slot] == 0) {
            insert(slot, key, value);
        } else {
            values[slot] = value;
        }
    }

    public long add(long key, long delta) {
        int slot = find(key);
        if (keys[slot] == 0) {
            insert(slot, key, delta);
            return delta;
        }
        values[slot] += delta;
        return values[slot];
    }

    private void insert(int slot, long key, long value) {
        keys[slot] = key;
        values[slot] = value;
        used[size++] = slot;
        if (size * 3L >= keys.length * 2L) {
            grow();
        }
    }

    public int size() {
        return size;
    }

    public void forEach(Visitor visitor) {
        for (int i = 0; i < size; i++) {
            visitor.accept(keys[used[i]], values[used[i]]);
        }
    }

    public void clear() {
        if (keys.length > RETAINED) {
            keys = new long[initial];
            values = new long[initial];
            used = new int[initial];
        } else {
            for (int i = 0; i < size; i++) {
                keys[used[i]] = 0;
            }
        }
        size = 0;
    }

    private void grow() {
        long[] oldKeys = keys;
        long[] oldValues = values;
        int[] oldUsed = used;
        keys = new long[oldKeys.length * 2];
        values = new long[oldValues.length * 2];
        used = new int[keys.length];
        int mask = keys.length - 1;
        for (int i = 0; i < size; i++) {
            long key = oldKeys[oldUsed[i]];
            int slot = slot(key);
            while (keys[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            keys[slot] = key;
            values[slot] = oldValues[oldUsed[i]];
            used[i] = slot;
        }
    }
}
