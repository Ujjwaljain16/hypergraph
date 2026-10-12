// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import io.hstore.engine.HStoreException;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.page.PageHeader;
import io.hstore.engine.page.PageType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Collections;
import java.util.List;

public final class NodeCodec {

    private NodeCodec() {
    }

    public static MemorySegment encode(Node node, long pageId, long epoch, int pageSize) {
        ByteCursor out = ByteCursor.fixed(pageSize);
        out.position(PageHeader.SIZE);
        Summary summary = node.summary();
        out.putSignedVarLong(summary.weightSum())
                .putSignedVarLong(summary.weightMin())
                .putSignedVarLong(summary.weightMax())
                .putSignedVarLong(summary.timeMin())
                .putSignedVarLong(summary.timeMax())
                .putLong(summary.roleBits());
        try {
            switch (node) {
                case Leaf leaf -> encodeLeaf(out, leaf);
                case Branch branch -> encodeBranch(out, branch);
            }
        } catch (IndexOutOfBoundsException overflow) {
            throw new IllegalStateException("node of " + node.size() + " entries overflows a " + pageSize + " byte page", overflow);
        }
        int payloadLength = Math.toIntExact(out.position() - PageHeader.SIZE);
        PageType type = node instanceof Leaf ? PageType.LEAF : PageType.INTERNAL;
        int flags = node instanceof Leaf leaf && refersToPages(leaf) ? PageHeader.REFERS_TO_PAGES : 0;
        new PageHeader(type, node.schema.id(), flags, pageId, epoch, payloadLength, node.size(), summary.count(),
                summary.min(), summary.max(), node.height(), summary.fingerprint()).writeTo(out);
        MemorySegment image = out.segment().asSlice(0, PageHeader.SIZE + payloadLength);
        PageHeader.seal(image, payloadLength);
        return image;
    }

    private static void encodeLeaf(ByteCursor out, Leaf leaf) {
        long[] keys = leaf.keyArray();
        out.putSignedVarLong(keys[0]);
        for (int i = 1; i < keys.length; i++) {
            out.putVarLong(keys[i] - keys[i - 1]);
        }
        encodeValues(out, leaf.schema, keys, leaf);
    }

    static void encodeInline(ByteCursor out, Leaf leaf) {
        out.putVarLong(leaf.size());
        encodeLeaf(out, leaf);
    }

    static Leaf decodeInline(ByteCursor in, TreeSchema<?> schema) {
        int count = Math.toIntExact(in.getVarLong());
        if (count < 1 || count > Ref.INLINE_MAX_ENTRIES) {
            throw new IllegalStateException("inline tree of " + count + " entries in " + schema);
        }
        return decodeLeaf(in, schema, decodeKeys(in, count), null);
    }

    private static Leaf decodeLeaf(ByteCursor in, TreeSchema<?> schema, long[] keys, Summary summary) {
        long[] words = schema.codec().decodeImplied(in, keys);
        if (words != null) {
            return Leaf.implied(schema, keys, words, summary);
        }
        Object[] values = new Object[keys.length];
        if (schema.codec().independentValues()) {
            decodeFramed(in, schema, keys, values);
        } else {
            schema.codec().decode(in, keys, values);
        }
        return Leaf.decoded(schema, keys, values, summary);
    }

    private static int[] offsets(ByteCursor in, int count, PageHeader header, long pageId) {
        int[] offsets = new int[count];
        long end = PageHeader.SIZE + (long) header.payloadLength();
        for (int i = 0; i < count; i++) {
            long length = in.getVarLong();
            offsets[i] = Math.toIntExact(in.position());
            if (length < 0 || in.position() + length > end) {
                throw HStoreException.corrupt(pageId, "value " + i + " runs past the end of the page");
            }
            in.skip(length);
        }
        if (in.position() != end) {
            throw HStoreException.corrupt(pageId, "values end at byte " + in.position() + ", not at the end of the payload " + end);
        }
        return offsets;
    }

    private static MemorySegment owned(MemorySegment page) {
        return page.isNative() ? MemorySegment.ofArray(page.toArray(ValueLayout.JAVA_BYTE)) : page;
    }

    private static void decodeFramed(ByteCursor in, TreeSchema<?> schema, long[] keys, Object[] into) {
        long[] key = new long[1];
        Object[] value = new Object[1];
        for (int i = 0; i < keys.length; i++) {
            long length = in.getVarLong();
            long end = in.position() + length;
            key[0] = keys[i];
            schema.codec().decode(in, key, value);
            if (in.position() != end) {
                throw new IllegalStateException("value " + i + " of a " + schema + " leaf is " + length + " bytes long but decoded from "
                        + (in.position() - end + length) + " bytes");
            }
            into[i] = value[0];
        }
    }

    private static long[] decodeKeys(ByteCursor in, int count) {
        long[] keys = new long[count];
        keys[0] = in.getSignedVarLong();
        for (int i = 1; i < count; i++) {
            keys[i] = keys[i - 1] + in.getVarLong();
        }
        return keys;
    }

    private static <V> void encodeValues(ByteCursor out, TreeSchema<V> schema, long[] keys, Leaf leaf) {
        long[] words = leaf.impliedWords();
        if (words != null) {
            schema.codec().encodeImplied(out, keys, words);
            return;
        }
        @SuppressWarnings("unchecked")
        List<V> values = (List<V>) leaf.valueList();
        if (!schema.codec().independentValues()) {
            schema.codec().encode(out, keys, values);
            return;
        }
        ByteCursor value = ByteCursor.growable(256);
        long[] key = new long[1];
        for (int i = 0; i < keys.length; i++) {
            value.position(0);
            key[0] = keys[i];
            schema.codec().encode(value, key, Collections.singletonList(values.get(i)));
            MemorySegment written = value.written();
            out.putVarLong(written.byteSize()).putSegment(written);
        }
    }

    private static boolean refersToPages(Leaf leaf) {
        return leaf.schema.codec().holdsRefs() && refersToPages(leaf.schema, leaf.valueList());
    }

    private static <V> boolean refersToPages(TreeSchema<V> schema, List<?> values) {
        for (Object value : values) {
            if (schema.codec().refersToPages(schema.cast(value))) {
                return true;
            }
        }
        return false;
    }

    private static void encodeBranch(ByteCursor out, Branch branch) {
        long[] separators = branch.separatorArray();
        Ref[] children = branch.childArray();
        out.putSignedVarLong(separators[0]);
        for (int i = 1; i < separators.length; i++) {
            out.putVarLong(separators[i] - separators[i - 1]);
        }
        for (Ref child : children) {
            Ref.write(out, child);
        }
    }

    public static Node decode(MemorySegment page, long pageId, TreeSchema<?> schema) {
        return decode(page, PageHeader.verify(page, pageId), pageId, schema);
    }

    public static Node decode(MemorySegment page, PageHeader header, long pageId, TreeSchema<?> schema) {
        if (header.schema() != schema.id()) {
            throw HStoreException.corrupt(pageId, "page belongs to schema " + header.schema() + ", expected " + schema);
        }
        ByteCursor in = ByteCursor.over(page).position(PageHeader.SIZE);
        Summary summary = new Summary(header.logicalCount(), header.lowerBound(), header.upperBound(),
                in.getSignedVarLong(), in.getSignedVarLong(), in.getSignedVarLong(),
                in.getSignedVarLong(), in.getSignedVarLong(), in.getLong(), header.fingerprint());
        int count = header.slotCount();
        long[] keys = decodeKeys(in, count);
        return switch (header.type()) {
            case LEAF -> {
                if (schema.codec().independentValues()) {
                    yield Leaf.packed(schema, keys, owned(page), offsets(in, count, header, pageId), summary);
                }
                Leaf leaf = decodeLeaf(in, schema, keys, summary);
                if (header.refersToPages() != refersToPages(leaf)) {
                    throw HStoreException.corrupt(pageId, "page header and contents disagree on whether it refers to other pages");
                }
                yield leaf;
            }
            case INTERNAL -> {
                Ref[] children = new Ref[count];
                for (int i = 0; i < count; i++) {
                    children[i] = Ref.read(in);
                }
                yield Branch.decoded(schema, keys, children, header.height(), summary);
            }
        };
    }
}
