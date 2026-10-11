// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.page.PageHeader;
import io.hstore.engine.page.PageId;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.TopologySchemas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeTest {

    static final TreeSchema<Long> LONGS = new TreeSchema<>(200, "longs", FingerprintMode.SET,
            ValueCodec.rows(ByteCursor::signedVarLongSize, ByteCursor::putSignedVarLong, ByteCursor::getSignedVarLong),
            (key, value, into) -> into.entry(key, Hashing.of(key, value)).weight(value));

    static RandomGenerator random(long seed) {
        return RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void randomMutationsMatchOracle(long seed) {
        RandomGenerator random = random(seed);
        MemoryPages pages = new MemoryPages(1024);
        WriteScope scope = new WriteScope();
        Tree<Long> tree = Tree.empty(LONGS, pages);
        TreeMap<Long, Long> oracle = new TreeMap<>();
        for (int step = 0; step < 6000; step++) {
            long key = random.nextLong(2000) - 1000;
            if (random.nextInt(10) < 6) {
                long value = random.nextLong(1000);
                tree = tree.put(scope, key, value);
                oracle.put(key, value);
            } else {
                tree = tree.remove(scope, key);
                oracle.remove(key);
            }
            if (step % 997 == 0) {
                tree = pages.persist(tree);
                scope.freeze();
            }
        }
        assertMatches(oracle, tree);
        assertMatches(oracle, pages.persist(tree));
    }

    @Test
    void keysAndCursorsWalkADeepTreeInOrder() {
        RandomGenerator random = random(9);
        MemoryPages pages = new MemoryPages(1024);
        WriteScope scope = new WriteScope();
        Tree<Long> tree = Tree.empty(LONGS, pages);
        TreeMap<Long, Long> oracle = new TreeMap<>();
        for (int step = 0; step < 400_000; step++) {
            long key = random.nextLong(100_000_000);
            tree = tree.put(scope, key, key % 97);
            oracle.put(key, key % 97);
            if (step % 49_999 == 0) {
                tree = pages.persist(tree);
                scope.freeze();
            }
        }
        tree = pages.persist(tree);
        assertTrue(tree.height() > 5, "height " + tree.height());
        long[] expected = oracle.keySet().stream().mapToLong(Long::longValue).toArray();
        assertArrayEquals(expected, tree.keys().toArray());
        LongStream.Builder visited = LongStream.builder();
        tree.forEachKey(visited::add);
        assertArrayEquals(expected, visited.build().toArray());
        assertArrayEquals(Arrays.copyOf(expected, 25), tree.keys().limit(25).toArray());
        List<Long> values = new ArrayList<>();
        tree.forEachValue(values::add);
        assertEquals(List.copyOf(oracle.values()), values);
        long[] reversed = LongStream.range(0, expected.length).map(i -> expected[expected.length - 1 - (int) i]).toArray();
        assertArrayEquals(reversed, tree.descending().mapToLong(Entry::key).toArray());
        for (int probe = 0; probe < 2_000; probe++) {
            long key = random.nextLong(100_000_100) - 50;
            assertEquals(Optional.ofNullable(oracle.ceilingKey(key)), tree.ceiling(key).map(Entry::key), "ceiling " + key);
            assertEquals(Optional.ofNullable(oracle.floorKey(key)), tree.floor(key).map(Entry::key), "floor " + key);
            TreeCursor<Long> cursor = tree.cursor();
            if (probe < 20 && cursor.seek(key)) {
                LongStream.Builder rest = LongStream.builder();
                cursor.forEachRemainingKey(rest::add);
                assertArrayEquals(oracle.tailMap(key, true).keySet().stream().mapToLong(Long::longValue).toArray(), rest.build().toArray());
            }
        }
    }

    @Test
    void permutationsYieldEqualFingerprints() {
        List<Long> keys = new ArrayList<>(LongStream.range(0, 3000).boxed().toList());
        MemoryPages pages = new MemoryPages(1024);
        Tree<Long> ascending = Tree.empty(LONGS, pages);
        for (long key : keys) {
            ascending = ascending.put(new WriteScope(), key, key * 3);
        }
        Collections.shuffle(keys, new Random(7));
        WriteScope scope = new WriteScope();
        Tree<Long> shuffled = Tree.empty(LONGS, pages);
        for (long key : keys) {
            shuffled = shuffled.put(scope, key, key * 3);
        }
        BulkBuilder<Long> builder = Tree.empty(LONGS, pages).builder(new WriteScope());
        LongStream.range(0, 3000).forEach(key -> builder.add(key, key * 3));
        Tree<Long> bulk = builder.build();
        assertEquals(ascending.summary(), shuffled.summary());
        assertEquals(ascending.summary(), bulk.summary());
        assertTrue(TreeAlgebra.equal(ascending, shuffled));
        assertTrue(TreeAlgebra.equal(pages.persist(bulk), shuffled));
        checkInvariants(bulk);
    }

    @Test
    void sequenceFingerprintIsAssociative() {
        TreeSchema<Long> sequence = new TreeSchema<>(201, "seq", FingerprintMode.SEQUENCE, LONGS.codec(),
                (key, value, into) -> into.entry(key, Hashing.mix(value)));
        MemoryPages pages = new MemoryPages(1024);
        BulkBuilder<Long> builder = Tree.empty(sequence, pages).builder(new WriteScope());
        LongStream.range(0, 5000).forEach(key -> builder.add(key * 10, key % 7));
        Tree<Long> bulk = builder.build();
        Tree<Long> incremental = Tree.empty(sequence, pages);
        WriteScope scope = new WriteScope();
        for (long key = 4999; key >= 0; key--) {
            incremental = incremental.put(scope, key * 10, key % 7);
        }
        assertEquals(bulk.summary().fingerprint(), incremental.summary().fingerprint());
        Tree<Long> swapped = incremental.put(scope, 0, 6L).put(scope, 10, 0L);
        assertFalse(bulk.summary().fingerprint() == swapped.summary().fingerprint());
    }

    @ParameterizedTest
    @ValueSource(longs = {11, 12, 13})
    void setAlgebraMatchesOracle(long seed) {
        RandomGenerator random = random(seed);
        MemoryPages pages = new MemoryPages(1024);
        TreeSet<Long> left = new TreeSet<>();
        TreeSet<Long> right = new TreeSet<>();
        TreeSet<Long> third = new TreeSet<>();
        for (int i = 0; i < 4000; i++) {
            left.add(random.nextLong(10_000));
            right.add(random.nextLong(10_000));
            third.add(random.nextLong(20_000));
        }
        Tree<Long> a = pages.persist(build(pages, left));
        Tree<Long> b = pages.persist(build(pages, right));
        Tree<Long> c = build(pages, third);
        TreeSet<Long> both = new TreeSet<>(left);
        both.retainAll(right);
        for (TreeAlgebra.Strategy strategy : TreeAlgebra.Strategy.values()) {
            assertEquals(both.size(), TreeAlgebra.countIntersect(a, b, strategy));
        }
        assertEquals(List.copyOf(both), TreeAlgebra.intersectKeys(a, b).boxed().toList());
        TreeSet<Long> all = new TreeSet<>(both);
        all.retainAll(third);
        assertEquals(List.copyOf(all), TreeAlgebra.intersectKeys(List.of(c, a, b)).boxed().toList());
        assertEquals(a.keys().boxed().toList(), TreeAlgebra.intersectKeys(List.of(a)).boxed().toList());
        TreeSet<Long> union = new TreeSet<>(left);
        union.addAll(right);
        assertMatches(asMap(union), TreeAlgebra.union(a, b, new WriteScope(), (x, _) -> x));
        TreeSet<Long> difference = new TreeSet<>(left);
        difference.removeAll(right);
        assertMatches(asMap(difference), TreeAlgebra.difference(a, b, new WriteScope()));
        assertMatches(asMap(both), TreeAlgebra.intersection(a, b, new WriteScope()));
        Tree<Long> subset = TreeAlgebra.intersection(a, b, new WriteScope());
        assertTrue(TreeAlgebra.subset(subset, a));
        assertFalse(TreeAlgebra.subset(a, subset));
        assertEquals(TreeAlgebra.union(a, a, new WriteScope(), (x, _) -> x), a);
        assertTrue(TreeAlgebra.difference(a, a, new WriteScope()).isEmpty());
        assertTrue(TreeAlgebra.equal(TreeAlgebra.intersection(a, a, new WriteScope()), a));
    }

    @Test
    void disjointUnionJoinsWithoutRebuilding() {
        MemoryPages pages = new MemoryPages(1024);
        TreeSet<Long> low = new TreeSet<>(LongStream.range(0, 3000).boxed().toList());
        TreeSet<Long> high = new TreeSet<>(LongStream.range(5000, 5040).boxed().toList());
        Tree<Long> a = pages.persist(build(pages, low));
        Tree<Long> b = pages.persist(build(pages, high));
        TreeSet<Long> all = new TreeSet<>(low);
        all.addAll(high);
        Tree<Long> joined = TreeAlgebra.union(a, b, new WriteScope(), (x, _) -> x);
        assertMatches(asMap(all), joined);
        assertMatches(asMap(all), TreeAlgebra.union(b, a, new WriteScope(), (x, _) -> x));
        checkInvariants(joined);
    }

    @Test
    void diffReportsExactDeltasAndSkipsSharedPages() {
        MemoryPages pages = new MemoryPages(1024);
        TreeMap<Long, Long> oracle = new TreeMap<>();
        LongStream.range(0, 20_000).forEach(key -> oracle.put(key, key));
        Tree<Long> before = pages.persist(build(pages, new TreeSet<>(oracle.keySet())));
        WriteScope scope = new WriteScope();
        Tree<Long> after = before.remove(scope, 17).put(scope, 30_000, 1L).put(scope, 500, 9L);
        pages.loads = 0;
        List<TreeDiff.Delta<Long>> deltas = TreeDiff.diff(before, after).toList();
        assertEquals(List.of(new TreeDiff.Delta.Removed<>(17, 17L), new TreeDiff.Delta.Changed<>(500, 500L, 9L),
                new TreeDiff.Delta.Added<>(30_000, 1L)), deltas);
        assertTrue(pages.loads < 40, "diff visited " + pages.loads + " pages");
    }

    @Test
    void keysetPaginationIsStable() {
        MemoryPages pages = new MemoryPages(1024);
        Tree<Long> tree = pages.persist(build(pages, new TreeSet<>(LongStream.range(0, 1000).map(x -> x * 2).boxed().toList())));
        List<Long> seen = new ArrayList<>();
        Optional<Keyset> cursor = Optional.of(Keyset.ascending());
        while (cursor.isPresent()) {
            Keyset.Slice<Long> slice = tree.slice(cursor.get(), 37);
            slice.entries().forEach(entry -> seen.add(entry.key()));
            cursor = slice.next();
        }
        assertEquals(tree.keys().boxed().toList(), seen);
    }

    static Tree<Long> build(MemoryPages pages, TreeSet<Long> keys) {
        BulkBuilder<Long> builder = Tree.empty(LONGS, pages).builder(new WriteScope());
        keys.forEach(key -> builder.add(key, key));
        return builder.build();
    }

    static TreeMap<Long, Long> asMap(TreeSet<Long> keys) {
        TreeMap<Long, Long> map = new TreeMap<>();
        keys.forEach(key -> map.put(key, key));
        return map;
    }

    static void assertMatches(TreeMap<Long, Long> oracle, Tree<Long> tree) {
        assertEquals(oracle.size(), tree.size());
        assertEquals(List.copyOf(oracle.keySet()), tree.keys().boxed().toList());
        assertEquals(List.copyOf(oracle.descendingKeySet()), tree.descending().map(Entry::key).toList());
        int rank = 0;
        for (Map.Entry<Long, Long> entry : oracle.entrySet()) {
            assertEquals(Optional.of(entry.getValue()), tree.get(entry.getKey()));
            assertEquals(rank, tree.rankOf(entry.getKey()));
            assertEquals(entry.getKey(), tree.at(rank).key());
            rank++;
        }
        if (!oracle.isEmpty()) {
            long low = oracle.firstKey() + 3;
            long high = low + 250;
            assertEquals(List.copyOf(oracle.subMap(low, true, high, true).keySet()),
                    tree.range(low, high).map(Entry::key).toList());
            assertEquals(Optional.ofNullable(oracle.ceilingKey(low + 1)), tree.ceiling(low + 1).map(Entry::key));
            assertEquals(Optional.ofNullable(oracle.floorKey(high - 1)), tree.floor(high - 1).map(Entry::key));
            assertEquals(oracle.values().stream().mapToLong(Long::longValue).sum(), tree.summary().weightSum());
        }
        checkInvariants(tree);
    }

    static void checkInvariants(Tree<?> tree) {
        if (tree.isEmpty()) {
            return;
        }
        Summary recomputed = verify(tree, tree.root());
        assertEquals(recomputed, tree.summary());
    }

    @Test
    void aLeafSaysInItsHeaderWhetherItRefersToOtherPages() {
        AtomRecord node = AtomRecord.NodeRecord.of(1, "n");
        AtomRecord edge = new AtomRecord.EdgeRecord(2, EdgeKind.SET, new Ref.Stored(PageId.pack(7, 0), 1, Summary.EMPTY), Ref.EMPTY, 0, 0, 0, 0);
        long pageId = PageId.pack(1, 0);
        for (List<AtomRecord> values : List.of(List.of(node), List.of(node, edge))) {
            Leaf leaf = Leaf.owned(EngineSlots.ATOM_SCHEMA, null, LongStream.range(0, values.size()).toArray(), values.toArray(), values.size());
            MemorySegment image = NodeCodec.encode(leaf, pageId, 1, 4096);
            assertEquals(values.contains(edge), PageHeader.verify(image, pageId).refersToPages());
            Leaf decoded = (Leaf) NodeCodec.decode(image, pageId, EngineSlots.ATOM_SCHEMA);
            assertEquals(values, decoded.valueList());
        }
    }

    @Test
    void inlineTreesRoundTripInsideTheRecordThatHoldsThem() {
        Leaf members = memberLeaf(10, 20, 30);
        AtomRecord edge = new AtomRecord.EdgeRecord(2, EdgeKind.SET, new Ref.Inline(members), Ref.EMPTY, 3, 0, 0, 0);
        AtomRecord stored = new AtomRecord.EdgeRecord(2, EdgeKind.SET, new Ref.Stored(PageId.pack(7, 0), 1, Summary.EMPTY), Ref.EMPTY, 0, 0, 0, 0);
        long pageId = PageId.pack(1, 0);
        for (List<AtomRecord> values : List.of(List.of(edge), List.of(edge, stored))) {
            Leaf leaf = Leaf.owned(EngineSlots.ATOM_SCHEMA, null, LongStream.range(0, values.size()).toArray(), values.toArray(), values.size());
            MemorySegment image = NodeCodec.encode(leaf, pageId, 1, 4096);
            assertEquals(values.contains(stored), PageHeader.verify(image, pageId).refersToPages());
            Leaf decoded = (Leaf) NodeCodec.decode(image, pageId, EngineSlots.ATOM_SCHEMA);
            assertEquals(values, decoded.valueList());
            AtomRecord.EdgeRecord read = (AtomRecord.EdgeRecord) decoded.value(0);
            Ref.Inline inline = (Ref.Inline) read.members();
            assertTrue(Ref.same(new Ref.Inline(members), inline));
            assertEquals(members.summary(), inline.summary());
            assertEquals(List.of(10L, 20L, 30L), new Tree<>(TopologySchemas.SET_MEMBERS, NodeSource.ephemeral(4096), inline).keys().boxed().toList());
        }
    }

    @Test
    void inlineReferencesAreTheSameOnlyWhenTheirEntriesAre() {
        Ref a = new Ref.Inline(memberLeaf(1, 2, 3));
        Ref b = new Ref.Inline(memberLeaf(1, 2, 3));
        Ref c = new Ref.Inline(memberLeaf(1, 2, 4));
        Leaf weighted = memberLeaf(1, 2, 3);
        Ref d = new Ref.Inline(Leaf.decoded(TopologySchemas.SET_MEMBERS, weighted.keyArray(),
                new Object[]{Incidence.of(1), Incidence.of(2), Incidence.of(3).withWeight(2.0)}, null));
        assertTrue(Ref.same(a, b));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(Ref.same(a, c));
        assertFalse(Ref.same(a, d));
        assertFalse(Ref.same(a, new Ref.Pending(memberLeaf(1, 2, 3))));
        assertFalse(Ref.same(a, Ref.EMPTY));
    }

    @Test
    void referencesCarryATagAndInlineTreesNeedTheirSchema() {
        Ref inline = new Ref.Inline(memberLeaf(5, 6));
        Ref stored = new Ref.Stored(PageId.pack(9, 0), 2, memberLeaf(5, 6).summary());
        ByteCursor out = ByteCursor.growable(256);
        for (Ref ref : List.of(Ref.EMPTY, stored, inline)) {
            long start = out.position();
            Ref.write(out, ref);
            assertTrue(out.position() - start <= Ref.encodedSize(ref));
            assertTrue(Ref.encodedSize(ref) <= Ref.maxEncodedSize());
        }
        ByteCursor in = ByteCursor.wrap(out.toByteArray());
        assertEquals(Ref.EMPTY, Ref.read(in, TopologySchemas.SET_MEMBERS));
        assertEquals(stored, Ref.read(in, TopologySchemas.SET_MEMBERS));
        assertEquals(inline, Ref.read(in, TopologySchemas.SET_MEMBERS));
        ByteCursor schemaless = ByteCursor.wrap(out.toByteArray());
        Ref.read(schemaless);
        Ref.read(schemaless);
        assertThrows(IllegalStateException.class, () -> Ref.read(schemaless));
    }

    @Test
    void onlySmallLeavesWithoutNestedTreesFitInline() {
        assertTrue(Ref.fitsInline(memberLeaf(LongStream.rangeClosed(1, Ref.INLINE_MAX_ENTRIES).toArray())));
        assertFalse(Ref.fitsInline(memberLeaf(LongStream.rangeClosed(1, Ref.INLINE_MAX_ENTRIES + 1).toArray())));
        assertFalse(Ref.fitsInline(memberLeaf(LongStream.rangeClosed(1, 20).map(i -> i << 40).toArray())));
        Leaf catalog = Leaf.owned(EngineSlots.ATOM_SCHEMA, null, new long[]{1}, new Object[]{AtomRecord.NodeRecord.of(1, "n")}, 1);
        assertFalse(Ref.fitsInline(catalog));
    }

    private static Leaf memberLeaf(long... members) {
        Object[] values = Arrays.stream(members).mapToObj(Incidence::of).toArray();
        return Leaf.decoded(TopologySchemas.SET_MEMBERS, members.clone(), values, null);
    }

    private static Summary verify(Tree<?> tree, Ref ref) {
        Node node = tree.node(ref);
        Summary.Builder builder = Summary.builder(tree.schema().fingerprint());
        switch (node) {
            case Leaf leaf -> {
                assertTrue(leaf.size() > 0, "empty leaf");
                for (int i = 0; i < leaf.size(); i++) {
                    if (i > 0) {
                        assertTrue(leaf.key(i - 1) < leaf.key(i), "leaf keys out of order");
                    }
                    tree.schema().measureEntry(leaf.key(i), leaf.value(i), builder);
                }
            }
            case Branch branch -> {
                assertTrue(branch.size() > 1, "unary branch");
                Summary previous = null;
                for (int i = 0; i < branch.size(); i++) {
                    Summary child = verify(tree, branch.child(i));
                    assertEquals(child, branch.child(i).summary(), "stale child summary");
                    assertEquals(node.height() - 1, tree.node(branch.child(i)).height(), "unbalanced");
                    if (previous != null) {
                        assertTrue(previous.max() < branch.separator(i) && branch.separator(i) <= child.min(), "bad separator");
                    }
                    builder.merge(child);
                    previous = child;
                }
            }
        }
        Summary summary = builder.build();
        assertEquals(summary, node.summary());
        assertEquals(summary.count(), node.count());
        return summary;
    }
}
