// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import io.hstore.engine.page.PageHeader;
import io.hstore.engine.page.PageId;

import java.lang.foreign.MemorySegment;
import java.util.IdentityHashMap;
import java.util.Map;

public final class Materializer {

    public interface Sink {
        long allocate(int length);

        void accept(long pageId, MemorySegment image, Node frozen);
    }

    private final Sink sink;
    private final long epoch;
    private final int pageSize;
    private final Map<Node, Ref> written = new IdentityHashMap<>();
    private long pages;

    public Materializer(Sink sink, long epoch, int pageSize) {
        this.sink = sink;
        this.epoch = epoch;
        this.pageSize = pageSize;
    }

    public Ref materialize(Ref ref, TreeSchema<?> schema) {
        if (!(ref instanceof Ref.Pending(Node node))) {
            return ref;
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
        written.put(node, stored);
        return stored;
    }

    public Ref writtenAs(Node node) {
        Ref ref = written.get(node);
        if (ref == null) {
            throw new IllegalStateException("node was not written by this materializer");
        }
        return ref;
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
            Ref inline = new Ref.Inline(freeze(leaf));
            written.put(leaf, inline);
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
}
