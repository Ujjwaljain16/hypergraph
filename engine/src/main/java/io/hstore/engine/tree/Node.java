// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import java.util.function.IntConsumer;

public sealed abstract class Node permits Leaf, Branch {

    static final int NODE_HEAP_BYTES = 144;
    static final int ARRAY_HEAP_BYTES = 16;

    private record Stamp(Ref ref, Epoch epoch) {
    }

    final TreeSchema<?> schema;
    final Object owner;
    private volatile Summary summary;
    private volatile Stamp stamp;

    Node(TreeSchema<?> schema, Object owner, Summary summary) {
        this.schema = schema;
        this.owner = owner;
        this.summary = summary;
    }

    public final Summary summary() {
        Summary cached = summary;
        if (cached == null) {
            cached = computeSummary();
            summary = cached;
        }
        return cached;
    }

    public final TreeSchema<?> schema() {
        return schema;
    }

    final void invalidate() {
        summary = null;
    }

    final void stamp(Ref written, Epoch epoch) {
        stamp = new Stamp(written, epoch);
    }

    final Ref writtenIn(Epoch epoch) {
        Stamp current = stamp;
        return current != null && current.epoch() == epoch ? current.ref() : null;
    }

    final Ref published() {
        Stamp current = stamp;
        return current != null && current.epoch().published() ? current.ref() : null;
    }

    final boolean writableBy(WriteScope scope) {
        return owner != null && owner == scope.token();
    }

    abstract Summary computeSummary();

    public abstract long count();

    public abstract int height();

    abstract int size();

    abstract int heapBytes();

    void chargeTo(IntConsumer charge) {
    }
}
