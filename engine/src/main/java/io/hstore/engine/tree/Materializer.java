// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import io.hstore.engine.page.PageHeader;
import io.hstore.engine.page.PageId;

import java.lang.foreign.MemorySegment;
import java.util.function.Consumer;

public final class Materializer {

    public interface Sink {
        long allocate(int length);

        void accept(long pageId, MemorySegment image, Node frozen);
    }

    public record Unwritten(long nodes, long heapBytes) {
    }

    private final Sink sink;
    private final long epoch;
    private final int pageSize;
    private final Epoch stamping;
    private final Epoch reusable;
    private long pages;

    public Materializer(Sink sink, long epoch, int pageSize) {
        this(sink, epoch, pageSize, null, null);
    }

    public Materializer(Sink sink, long epoch, int pageSize, Epoch stamping, Epoch reusable) {
        this.sink = sink;
        this.epoch = epoch;
        this.pageSize = pageSize;
        this.stamping = stamping;
        this.reusable = reusable;
    }

    public Ref materialize(Ref ref, TreeSchema<?> schema) {
        if (!(ref instanceof Ref.Pending(Node node))) {
            return ref;
        }
        Ref reused = reused(node);
        if (reused != null) {
            return reused;
        }
        Node frozen = switch (node) {
            case Leaf leaf -> freeze(leaf);
            case Branch branch -> freeze(branch);
        };
        MemorySegment image = NodeCodec.encode(frozen, PageId.NONE, epoch, pageSize);
        long pageId = sink.allocate(Math.toIntExact(image.byteSize()));
        PageHeader.assign(image, pageId);
        sink.accept(pageId, image, frozen);
        pages++;
        Ref stored = new Ref.Stored(pageId, PageId.unitsFor(image.byteSize()), frozen.summary());
        if (stamping != null) {
            node.stamp(stored, stamping);
        }
        return stored;
    }

    private Ref reused(Node node) {
        if (reusable == null) {
            return null;
        }
        Ref written = node.writtenIn(reusable);
        if (written != null && stamping != null) {
            node.stamp(written, stamping);
        }
        return written;
    }

    public long pagesWritten() {
        return pages;
    }

    public static long pendingNodes(Ref ref, TreeSchema<?> schema) {
        if (!(ref instanceof Ref.Pending(Node node))) {
            return 0;
        }
        long[] count = {1};
        switch (node) {
            case Leaf leaf -> {
                if (leaf.schema.codec().holdsRefs()) {
                    boolean inlines = leaf.schema.codec().inlinesSmallTrees();
                    leaf.valueList().forEach(value -> countNested(leaf.schema, value, inlines, count));
                }
            }
            case Branch branch -> {
                for (Ref child : branch.childArray()) {
                    count[0] += pendingNodes(child, branch.schema);
                }
            }
        }
        return count[0];
    }

    private static <V> void countNested(TreeSchema<V> schema, Object value, boolean inlines, long[] count) {
        schema.codec().forEachRef(schema.cast(value), (nested, nestedSchema) -> {
            if (!(inlines && staysInline(nested))) {
                count[0] += pendingNodes(nested, nestedSchema);
            }
        });
    }

    private static boolean staysInline(Ref ref) {
        return ref instanceof Ref.Pending(Leaf leaf) && Ref.fitsInline(leaf);
    }

    private Leaf freeze(Leaf leaf) {
        Object[] values = leaf.valueList().toArray();
        if (leaf.schema.codec().holdsRefs()) {
            ValueCodec.RefMapper nested = leaf.schema.codec().inlinesSmallTrees() ? this::inlineOrMaterialize : this::materialize;
            for (int i = 0; i < values.length; i++) {
                values[i] = mapRefs(leaf.schema, values[i], nested);
            }
        }
        return leaf.frozenWith(values);
    }

    private Ref inlineOrMaterialize(Ref ref, TreeSchema<?> schema) {
        if (ref instanceof Ref.Pending(Leaf leaf) && Ref.fitsInline(leaf)) {
            Ref reusedInline = reused(leaf);
            if (reusedInline != null) {
                return reusedInline;
            }
            Ref inline = new Ref.Inline(freeze(leaf));
            if (stamping != null) {
                leaf.stamp(inline, stamping);
            }
            return inline;
        }
        return materialize(ref, schema);
    }

    private static <V> Object mapRefs(TreeSchema<V> schema, Object value, ValueCodec.RefMapper mapper) {
        return schema.codec().mapRefs(schema.cast(value), mapper);
    }

    private Branch freeze(Branch branch) {
        Ref[] children = branch.childArray();
        for (int i = 0; i < children.length; i++) {
            children[i] = materialize(children[i], branch.schema);
        }
        return branch.frozenWith(children);
    }
    public static Unwritten unwritten(Ref ref, TreeSchema<?> schema, WriteScope scope) {
        long[] totals = new long[2];
        countOwned(ref, scope, totals);
        return new Unwritten(totals[0], totals[1]);
    }

    private static void countOwned(Ref ref, WriteScope scope, long[] totals) {
        if (!(ref instanceof Ref.Pending(Node node)) || !node.writableBy(scope)) {
            return;
        }
        totals[0]++;
        totals[1] += node.heapBytes();
        switch (node) {
            case Leaf leaf -> {
                if (leaf.schema.codec().holdsRefs()) {
                    boolean inlines = leaf.schema.codec().inlinesSmallTrees();
                    leaf.valueList().forEach(value -> forEachNested(leaf.schema, value, nested -> {
                        if (!(inlines && staysInline(nested))) {
                            countOwned(nested, scope, totals);
                        }
                    }));
                }
            }
            case Branch branch -> {
                for (Ref child : branch.childArray()) {
                    countOwned(child, scope, totals);
                }
            }
        }
    }

    private static <V> void forEachNested(TreeSchema<V> schema, Object value, Consumer<Ref> action) {
        schema.codec().forEachRef(schema.cast(value), (nested, _) -> action.accept(nested));
    }

    public static long unwrittenHeap(Ref ref) {
        if (!(ref instanceof Ref.Pending(Node node)) || node.published() != null) {
            return 0;
        }
        long[] total = {node.heapBytes()};
        switch (node) {
            case Leaf leaf -> {
                if (leaf.schema.codec().holdsRefs()) {
                    leaf.valueList().forEach(value -> forEachNested(leaf.schema, value, nested -> total[0] += unwrittenHeap(nested)));
                }
            }
            case Branch branch -> {
                for (Ref child : branch.childArray()) {
                    total[0] += unwrittenHeap(child);
                }
            }
        }
        return total[0];
    }

    public static Ref rebound(Ref ref, TreeSchema<?> schema, Epoch epoch) {
        if (!(ref instanceof Ref.Pending(Node node))) {
            return ref;
        }
        Ref written = node.writtenIn(epoch);
        if (written != null) {
            return written;
        }
        return switch (node) {
            case Leaf leaf -> {
                if (!leaf.schema.codec().holdsRefs()) {
                    yield ref;
                }
                Object[] values = leaf.valueList().toArray();
                boolean changed = false;
                for (int i = 0; i < values.length; i++) {
                    boolean[] moved = {false};
                    Object mapped = mapRefs(leaf.schema, values[i], (nested, nestedSchema) -> {
                        Ref rebound = rebound(nested, nestedSchema, epoch);
                        moved[0] |= rebound != nested;
                        return rebound;
                    });
                    if (moved[0]) {
                        values[i] = mapped;
                        changed = true;
                    }
                }
                yield changed ? new Ref.Pending(leaf.frozenWith(values)) : ref;
            }
            case Branch branch -> {
                Ref[] children = branch.childArray();
                boolean changed = false;
                for (int i = 0; i < children.length; i++) {
                    Ref rebound = rebound(children[i], branch.schema, epoch);
                    changed |= rebound != children[i];
                    children[i] = rebound;
                }
                yield changed ? new Ref.Pending(branch.frozenWith(children)) : ref;
            }
        };
    }
}
