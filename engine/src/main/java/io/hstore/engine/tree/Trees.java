// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

final class Trees {

    static final int INTERPOLATE_FROM = 16;
    static final int INTERPOLATE_WINDOW = 8;

    private Trees() {
    }

    static Node node(NodeSource source, TreeSchema<?> schema, Ref ref) {
        return switch (ref) {
            case Ref.Pending(Node node) -> node;
            case Ref.Inline(Leaf leaf) -> leaf;
            case Ref.Stored(long pageId, int _, Summary _) -> source.load(pageId, schema);
            case Ref.Empty _ -> throw new IllegalStateException("empty reference has no node");
        };
    }
}
