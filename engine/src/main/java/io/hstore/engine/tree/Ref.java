// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.page.PageId;

public sealed interface Ref {

    Ref EMPTY = new Empty();

    int MAX_UNITS_BYTES = 3;

    int INLINE_MAX_ENTRIES = 32;

    int EMPTY_TAG = 0;

    int STORED_TAG = 1;

    int INLINE_TAG = 2;

    Summary summary();

    long count();

    record Empty() implements Ref {
        @Override
        public Summary summary() {
            return Summary.EMPTY;
        }

        @Override
        public long count() {
            return 0;
        }
    }

    record Stored(long pageId, int units, Summary summary) implements Ref {
        @Override
        public long count() {
            return summary.count();
        }

        @Override
        public String toString() {
            return "Stored[" + PageId.unpack(pageId) + ", n=" + summary.count() + "]";
        }
    }

    record Pending(Node node) implements Ref {
        @Override
        public Summary summary() {
            return node.summary();
        }

        @Override
        public long count() {
            return node.count();
        }
    }

    record Inline(Leaf leaf) implements Ref {
        public Inline {
            if (leaf.owner != null || !fitsInline(leaf)) {
                throw new IllegalArgumentException("only a small frozen leaf without nested trees can be inline, not " + leaf.size()
                        + " entries of " + leaf.schema);
            }
        }

        @Override
        public Summary summary() {
            return leaf.summary();
        }

        @Override
        public long count() {
            return leaf.count();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Inline(Leaf that) && leaf.sameEntries(that);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(leaf.summary().fingerprint()) * 31 + leaf.size();
        }

        @Override
        public String toString() {
            return "Inline[" + leaf.schema + ", n=" + leaf.size() + "]";
        }
    }

    static boolean same(Ref a, Ref b) {
        return switch (a) {
            case Empty _ -> b instanceof Empty;
            case Stored(long page, _, _) -> b instanceof Stored(long other, _, _) && page == other;
            case Pending(Node node) -> b instanceof Pending(Node other) && node == other;
            case Inline inline -> inline.equals(b);
        };
    }

    static boolean fitsInline(Leaf leaf) {
        return leaf.size() > 0 && leaf.size() <= INLINE_MAX_ENTRIES && !leaf.schema.codec().holdsRefs()
                && inlineSize(leaf) <= maxEncodedSize();
    }

    static void write(ByteCursor out, Ref ref) {
        switch (ref) {
            case Empty _ -> out.putByte(EMPTY_TAG);
            case Stored(long pageId, int units, Summary summary) -> {
                out.putByte(STORED_TAG).putLong(pageId).putVarLong(units);
                summary.writeTo(out);
            }
            case Inline(Leaf leaf) -> {
                out.putByte(INLINE_TAG);
                NodeCodec.encodeInline(out, leaf);
            }
            case Pending _ -> throw new IllegalStateException("pending reference cannot be encoded");
        }
    }

    static Ref read(ByteCursor in) {
        int tag = in.getUnsignedByte();
        return switch (tag) {
            case EMPTY_TAG -> EMPTY;
            case STORED_TAG -> readStored(in);
            case INLINE_TAG -> throw new IllegalStateException("an inline tree can only be read where its schema is known");
            default -> throw new IllegalStateException("unknown tree reference tag " + tag);
        };
    }

    static Ref read(ByteCursor in, TreeSchema<?> schema) {
        int tag = in.getUnsignedByte();
        return switch (tag) {
            case EMPTY_TAG -> EMPTY;
            case STORED_TAG -> readStored(in);
            case INLINE_TAG -> new Inline(NodeCodec.decodeInline(in, schema));
            default -> throw new IllegalStateException("unknown tree reference tag " + tag);
        };
    }

    private static Ref readStored(ByteCursor in) {
        long pageId = in.getLong();
        return new Stored(pageId, Math.toIntExact(in.getVarLong()), Summary.readFrom(in));
    }

    static int encodedSize(Ref ref) {
        return switch (ref) {
            case Empty _ -> 1;
            case Stored(long _, int units, Summary summary) -> 1 + 8 + ByteCursor.varLongSize(units) + summary.encodedSize();
            case Inline(Leaf leaf) -> inlineSize(leaf);
            case Pending _ -> maxEncodedSize();
        };
    }

    static int maxEncodedSize() {
        return 1 + 8 + MAX_UNITS_BYTES + Summary.MAX_ENCODED_BYTES;
    }

    private static int inlineSize(Leaf leaf) {
        return 1 + ByteCursor.varLongSize(leaf.size()) + leaf.bytes();
    }
}
