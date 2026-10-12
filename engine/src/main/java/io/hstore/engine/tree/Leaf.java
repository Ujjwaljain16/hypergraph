// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import io.hstore.engine.page.ByteCursor;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.LongConsumer;

public final class Leaf extends Node {

    private static final int ENTRY_HEAP_BYTES = 96;

    private long[] keys;
    private Object[] values;
    private final long[] words;
    private int size;
    private int keyBytes;
    private int valueBytes;
    private volatile boolean measured;

    private Leaf(TreeSchema<?> schema, Object owner, long[] keys, Object[] values, long[] words, int size, Summary summary) {
        super(schema, owner, summary);
        this.keys = keys;
        this.values = values;
        this.words = words;
        this.size = size;
        if (owner != null) {
            remeasure();
        }
    }

    private Leaf(TreeSchema<?> schema, Object owner, long[] keys, Object[] values, int size, Summary summary) {
        this(schema, owner, keys, values, null, size, summary);
    }

    static Leaf owned(TreeSchema<?> schema, Object owner, long[] keys, Object[] values, int size) {
        return new Leaf(schema, owner, keys, values, size, null);
    }

    static Leaf decoded(TreeSchema<?> schema, long[] keys, Object[] values, Summary summary) {
        return new Leaf(schema, null, keys, values, keys.length, summary);
    }

    static Leaf implied(TreeSchema<?> schema, long[] keys, long[] words, Summary summary) {
        return new Leaf(schema, null, keys, null, words, keys.length, summary);
    }

    static int keyStreamBytes(long[] keys, int size) {
        if (size == 0) {
            return 0;
        }
        int total = ByteCursor.signedVarLongSize(keys[0]);
        for (int i = 1; i < size; i++) {
            total += ByteCursor.varLongSize(keys[i] - keys[i - 1]);
        }
        return total;
    }

    private static int gap(long low, long high) {
        return ByteCursor.varLongSize(high - low);
    }

    private void remeasure() {
        int valueTotal = 0;
        for (int i = 0; i < size; i++) {
            valueTotal += values != null ? schema.entryBytes(values[i]) : schema.impliedBytes(words[i]);
        }
        keyBytes = keyStreamBytes(keys, size);
        valueBytes = valueTotal;
        measured = true;
    }

    private void measure() {
        if (!measured) {
            remeasure();
        }
    }

    @Override
    int heapBytes() {
        if (values == null) {
            return NODE_HEAP_BYTES + 2 * ARRAY_HEAP_BYTES + Long.BYTES * keys.length + (words == keys ? 0 : Long.BYTES * words.length);
        }
        measure();
        int bytes = NODE_HEAP_BYTES + 2 * ARRAY_HEAP_BYTES + Long.BYTES * keys.length + Integer.BYTES * values.length
                + ENTRY_HEAP_BYTES * size + valueBytes;
        return schema.codec().holdsRefs() ? bytes + inlineHeapBytes(schema) : bytes;
    }

    private <V> int inlineHeapBytes(TreeSchema<V> typed) {
        int[] total = new int[1];
        ValueCodec.RefVisitor visitor = (ref, _) -> {
            if (ref instanceof Ref.Inline(Leaf inline)) {
                total[0] += inline.heapBytes();
            }
        };
        for (int i = 0; i < size; i++) {
            typed.codec().forEachRef(typed.cast(value(i)), visitor);
        }
        return total[0];
    }

    boolean sameEntries(Leaf other) {
        if (this == other) {
            return true;
        }
        if (schema.id() != other.schema.id() || size != other.size || !Arrays.equals(keys, 0, size, other.keys, 0, size)) {
            return false;
        }
        if (values == null && other.values == null) {
            return Arrays.equals(words, 0, size, other.words, 0, size);
        }
        for (int i = 0; i < size; i++) {
            if (!Objects.equals(value(i), other.value(i))) {
                return false;
            }
        }
        return true;
    }

    int search(long key) {
        if (size >= Trees.INTERPOLATE_FROM) {
            long low = keys[0];
            long high = keys[size - 1];
            if (key > low && key < high) {
                int guess = Math.clamp((long) (((double) key - low) / ((double) high - low) * (size - 1)), 0, size - 1);
                long found = keys[guess];
                if (found == key) {
                    return guess;
                }
                if (found < key) {
                    int end = Math.min(size, guess + Trees.INTERPOLATE_WINDOW + 1);
                    return keys[end - 1] >= key ? Arrays.binarySearch(keys, guess + 1, end, key) : Arrays.binarySearch(keys, end, size, key);
                }
                int start = Math.max(0, guess - Trees.INTERPOLATE_WINDOW);
                return keys[start] <= key ? Arrays.binarySearch(keys, start, guess, key) : Arrays.binarySearch(keys, 0, start, key);
            }
        }
        return Arrays.binarySearch(keys, 0, size, key);
    }

    long key(int index) {
        return keys[index];
    }

    void forEachKey(LongConsumer action) {
        for (int i = 0; i < size; i++) {
            action.accept(keys[i]);
        }
    }

    long[] impliedWords() {
        return values == null ? words : null;
    }

    Object value(int index) {
        Object[] stored = values;
        return stored != null ? stored[index] : schema.implied(words[index]);
    }

    int bytes() {
        measure();
        return keyBytes + valueBytes + schema.leafOverhead(size);
    }

    long firstKey() {
        return keys[0];
    }

    long lastKey() {
        return keys[size - 1];
    }

    List<Object> valueList() {
        return new AbstractList<>() {
            @Override
            public Object get(int index) {
                return value(index);
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    long[] keyArray() {
        return size == keys.length ? keys : Arrays.copyOf(keys, size);
    }

    Leaf copyFor(WriteScope scope) {
        int capacity = size + Math.max(4, size >> 3);
        return new Leaf(schema, scope.token(), Arrays.copyOf(keys, capacity), valueArray(capacity), size, null);
    }

    Leaf frozenWith(Object[] mappedValues) {
        long[] frozenKeys = keyArray();
        long[] implied = schema.impliedWords(frozenKeys, Arrays.asList(mappedValues).subList(0, size));
        return implied != null
                ? new Leaf(schema, null, frozenKeys, null, implied, size, summary())
                : new Leaf(schema, null, frozenKeys, Arrays.copyOf(mappedValues, size), size, summary());
    }

    private Object[] valueArray(int capacity) {
        if (values != null) {
            return Arrays.copyOf(values, capacity);
        }
        Object[] copy = new Object[capacity];
        for (int i = 0; i < size; i++) {
            copy[i] = value(i);
        }
        return copy;
    }

    void insert(int position, long key, Object value) {
        measure();
        if (size == keys.length) {
            int capacity = Math.max(8, size + (size >> 1));
            keys = Arrays.copyOf(keys, capacity);
            values = Arrays.copyOf(values, capacity);
        }
        keyBytes += insertionDelta(position, key);
        System.arraycopy(keys, position, keys, position + 1, size - position);
        System.arraycopy(values, position, values, position + 1, size - position);
        keys[position] = key;
        values[position] = value;
        size++;
        valueBytes += schema.entryBytes(value);
        invalidate();
    }

    private int insertionDelta(int position, long key) {
        if (size == 0) {
            return ByteCursor.signedVarLongSize(key);
        }
        if (position == 0) {
            return ByteCursor.signedVarLongSize(key) - ByteCursor.signedVarLongSize(keys[0]) + gap(key, keys[0]);
        }
        if (position == size) {
            return gap(keys[size - 1], key);
        }
        return gap(keys[position - 1], key) + gap(key, keys[position]) - gap(keys[position - 1], keys[position]);
    }

    void replace(int position, Object value) {
        measure();
        valueBytes += schema.entryBytes(value) - schema.entryBytes(values[position]);
        values[position] = value;
        invalidate();
    }

    void remove(int position) {
        measure();
        long key = keys[position];
        valueBytes -= schema.entryBytes(values[position]);
        System.arraycopy(keys, position + 1, keys, position, size - position - 1);
        System.arraycopy(values, position + 1, values, position, size - position - 1);
        values[--size] = null;
        keyBytes -= insertionDelta(position, key);
        invalidate();
    }

    Leaf splitOff(int budget) {
        int total = bytes();
        int cut = 1;
        int running = cost(0);
        while (cut < size - 1 && running < total / 2) {
            running += cost(cut++);
        }
        while (cut < size - 1 && total - running > budget) {
            running += cost(cut++);
        }
        int rightSize = size - cut;
        long[] rightKeys = Arrays.copyOfRange(keys, cut, cut + rightSize + 4);
        Object[] rightValues = Arrays.copyOfRange(values, cut, cut + rightSize + 4);
        Leaf right = owned(schema, owner, rightKeys, rightValues, rightSize);
        Arrays.fill(values, cut, size, null);
        size = cut;
        remeasure();
        invalidate();
        return right;
    }

    private int cost(int index) {
        int key = index == 0 ? ByteCursor.signedVarLongSize(keys[0]) : gap(keys[index - 1], keys[index]);
        return key + schema.entryBytes(value(index));
    }

    static Leaf concat(TreeSchema<?> schema, Object owner, Leaf left, Leaf right) {
        int total = left.size + right.size;
        long[] keys = Arrays.copyOf(left.keys, total + 4);
        Object[] values = left.valueArray(total + 4);
        System.arraycopy(right.keys, 0, keys, left.size, right.size);
        System.arraycopy(right.valueArray(right.size), 0, values, left.size, right.size);
        return owned(schema, owner, keys, values, total);
    }

    @Override
    Summary computeSummary() {
        Summary.Builder builder = Summary.builder(schema.fingerprint());
        for (int i = 0; i < size; i++) {
            schema.measureEntry(keys[i], value(i), builder);
        }
        return builder.build();
    }

    @Override
    public long count() {
        return size;
    }

    @Override
    public int height() {
        return 0;
    }

    @Override
    int size() {
        return size;
    }
}
