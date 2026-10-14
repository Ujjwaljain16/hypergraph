// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import io.hstore.engine.page.ByteCursor;

import java.util.List;

public record TreeSchema<V>(int id, String name, FingerprintMode fingerprint, ValueCodec<V> codec, EntryMeasure<V> measure) {

    public static final int KEY_BYTES = 10;

    public TreeSchema {
        if (id < 1 || id > 255) {
            throw new IllegalArgumentException("schema id must fit in one byte: " + id);
        }
    }

    @SuppressWarnings("unchecked")
    public V cast(Object value) {
        return (V) value;
    }

    int entryBytes(Object value) {
        int size = codec.maxSize(cast(value));
        return codec.independentValues() ? size + ByteCursor.varLongSize(size) : size;
    }

    Object implied(long word) {
        return codec.implied(word);
    }

    int impliedBytes(long word) {
        return codec.impliedSize(word);
    }

    @SuppressWarnings("unchecked")
    long[] impliedWords(long[] keys, List<?> values) {
        return codec.impliedWords(keys, (List<V>) values);
    }

    int leafOverhead(int entries) {
        return codec.leafOverhead(entries);
    }

    void measureEntry(long key, Object value, Summary.Builder into) {
        measure.measure(key, cast(value), into);
    }

    @Override
    public String toString() {
        return name + "#" + id;
    }
}
