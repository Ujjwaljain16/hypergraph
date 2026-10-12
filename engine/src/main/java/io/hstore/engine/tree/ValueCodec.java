// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import io.hstore.engine.page.ByteCursor;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

public interface ValueCodec<V> {

    int maxSize(V value);

    void encode(ByteCursor out, long[] keys, List<V> values);

    void decode(ByteCursor in, long[] keys, Object[] into);

    default long[] decodeImplied(ByteCursor in, long[] keys) {
        return null;
    }

    default long[] impliedWords(long[] keys, List<V> values) {
        return null;
    }

    default void encodeImplied(ByteCursor out, long[] keys, long[] words) {
        encode(out, keys, Arrays.stream(words).mapToObj(this::implied).toList());
    }

    default V implied(long word) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " has no values implied by a single word");
    }

    default int impliedSize(long word) {
        return maxSize(implied(word));
    }

    default int leafOverhead(int entries) {
        return 0;
    }

    default boolean holdsRefs() {
        return false;
    }

    default boolean independentValues() {
        return false;
    }

    default V mapRefs(V value, RefMapper mapper) {
        return value;
    }

    default boolean inlinesSmallTrees() {
        return false;
    }

    default void forEachRef(V value, RefVisitor visitor) {
        mapRefs(value, (ref, schema) -> {
            visitor.visit(ref, schema);
            return ref;
        });
    }

    default boolean refersToPages(V value) {
        boolean[] found = new boolean[1];
        forEachRef(value, (ref, _) -> found[0] |= ref instanceof Ref.Stored);
        return found[0];
    }

    static <V> ValueCodec<V> rows(ToIntFunction<V> size, BiConsumer<ByteCursor, V> writer, Function<ByteCursor, V> reader) {
        return new ValueCodec<>() {
            @Override
            public int maxSize(V value) {
                return size.applyAsInt(value);
            }

            @Override
            public void encode(ByteCursor out, long[] keys, List<V> values) {
                values.forEach(value -> writer.accept(out, value));
            }

            @Override
            public void decode(ByteCursor in, long[] keys, Object[] into) {
                for (int i = 0; i < into.length; i++) {
                    into[i] = reader.apply(in);
                }
            }

            @Override
            public boolean independentValues() {
                return true;
            }
        };
    }

    @FunctionalInterface
    interface RefMapper {
        Ref map(Ref ref, TreeSchema<?> schema);
    }

    @FunctionalInterface
    interface RefVisitor {
        void visit(Ref ref, TreeSchema<?> schema);
    }
}
