// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

public final class TreeWalker {

    public static final int UNKNOWN_HEIGHT = -1;

    @FunctionalInterface
    public interface Reference {
        void accept(long pageId, TreeSchema<?> schema, int height);
    }

    private final NodeSource source;

    public TreeWalker(NodeSource source) {
        this.source = source;
    }

    public void references(long pageId, TreeSchema<?> schema, int height, Reference into) {
        if (height == 0 && !schema.codec().holdsRefs()) {
            return;
        }
        Node node = source.loadIfReferencing(pageId, schema);
        if (node == null) {
            return;
        }
        switch (node) {
            case Leaf leaf -> {
                if (schema.codec().holdsRefs()) {
                    ValueCodec.RefVisitor visitor = (nested, nestedSchema) -> {
                        if (nested instanceof Ref.Stored(long child, int _, Summary _)) {
                            into.accept(child, nestedSchema, UNKNOWN_HEIGHT);
                        }
                    };
                    for (int i = 0; i < leaf.size(); i++) {
                        nested(schema, leaf.value(i), visitor);
                    }
                }
            }
            case Branch branch -> {
                for (int i = 0; i < branch.size(); i++) {
                    if (branch.child(i) instanceof Ref.Stored(long child, int _, Summary _)) {
                        into.accept(child, schema, branch.height() - 1);
                    }
                }
            }
        }
    }

    public static void forEachStored(Ref ref, TreeSchema<?> schema, Reference into) {
        switch (ref) {
            case Ref.Stored(long pageId, int _, Summary _) -> into.accept(pageId, schema, UNKNOWN_HEIGHT);
            case Ref.Pending(Leaf leaf) -> {
                if (schema.codec().holdsRefs()) {
                    for (int i = 0; i < leaf.size(); i++) {
                        nested(schema, leaf.value(i), (nested, nestedSchema) -> forEachStored(nested, nestedSchema, into));
                    }
                }
            }
            case Ref.Pending(Branch branch) -> {
                for (int i = 0; i < branch.size(); i++) {
                    forEachStored(branch.child(i), schema, into);
                }
            }
            default -> {
            }
        }
    }

    private static <V> void nested(TreeSchema<V> schema, Object value, ValueCodec.RefVisitor visitor) {
        schema.codec().forEachRef(schema.cast(value), visitor);
    }
}
