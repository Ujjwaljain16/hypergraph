// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.page.PageHeader;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.TopologySchemas;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackedLeafTest {

    private static final int PAGE = 16 * 1024;
    private static final int RECORDS = 60;

    private final MemoryPages pages = new MemoryPages(PAGE);
    private final List<AtomRecord> expected = new ArrayList<>();
    private final Ref root;
    private final long rootPage;

    PackedLeafTest() {
        WriteScope scope = new WriteScope();
        Tree<AtomRecord> catalog = Tree.empty(EngineSlots.ATOM_SCHEMA, pages);
        for (int atom = 0; atom < RECORDS; atom++) {
            AtomRecord record = atom % 3 == 0 ? AtomRecord.NodeRecord.of(1, "node-" + atom) : edge(scope, atom);
            catalog = catalog.put(scope, atom, record);
        }
        Tree<AtomRecord> written = pages.persist(catalog);
        root = written.root();
        rootPage = ((Ref.Stored) root).pageId();
        written.stream().forEach(entry -> expected.add(entry.value()));
    }

    private AtomRecord edge(WriteScope scope, int atom) {
        Tree<Incidence> members = Tree.empty(TopologySchemas.SET_MEMBERS, pages);
        for (long member = 1; member <= 1 + atom % 6; member++) {
            members = members.put(scope, 1_000L * atom + member, Incidence.of(1_000L * atom + member));
        }
        return new AtomRecord.EdgeRecord(2, EdgeKind.SET, members.root(), Ref.EMPTY, atom, 0, 0, 0);
    }

    private Leaf load() {
        return (Leaf) NodeCodec.decode(pages.image(rootPage), rootPage, EngineSlots.ATOM_SCHEMA);
    }

    @Test
    void aPackedLeafDecodesEachRecordOnlyWhenItIsRead() {
        Leaf leaf = load();
        assertEquals(RECORDS, leaf.size());
        int packedOnly = leaf.heapBytes();
        assertEquals(expected.get(17), leaf.value(17));
        int oneRecord = leaf.heapBytes();
        assertTrue(oneRecord > packedOnly);
        assertEquals(expected.get(17), leaf.value(17));
        assertEquals(oneRecord, leaf.heapBytes(), "a record read twice is unpacked once");
        for (int i = 0; i < RECORDS; i++) {
            assertEquals(expected.get(i), leaf.value(i));
        }
        Leaf eager = Leaf.decoded(EngineSlots.ATOM_SCHEMA, leaf.keyArray(), expected.toArray(), null);
        assertTrue(leaf.sameEntries(eager));
        assertEquals(eager.bytes(), leaf.bytes());
    }

    @Test
    void threadsReadingOneLeafSeeTheSameRecordsAndChargeEachOnce() throws Exception {
        Leaf leaf = load();
        int packedOnly = leaf.heapBytes();
        AtomicLong charged = new AtomicLong();
        leaf.chargeTo(charged::addAndGet);
        List<Future<?>> readers = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int thread = 0; thread < 8; thread++) {
                RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(thread);
                readers.add(pool.submit(() -> {
                    for (int round = 0; round < 2_000; round++) {
                        int i = random.nextInt(RECORDS);
                        assertEquals(expected.get(i), leaf.value(i));
                    }
                }));
            }
            for (Future<?> reader : readers) {
                reader.get();
            }
        }
        for (int i = 0; i < RECORDS; i++) {
            leaf.value(i);
        }
        assertEquals(leaf.heapBytes() - packedOnly, charged.get());
    }

    @Test
    void theCacheCountsRecordsAsTheyAreUnpackedAndStaysWithinBudget() {
        Leaf leaf = load();
        NodeCache cache = new NodeCache(1 << 20, PAGE);
        cache.put(rootPage, leaf, leaf.heapBytes());
        long before = cache.residentBytes();
        for (int i = 0; i < RECORDS; i++) {
            leaf.value(i);
        }
        assertTrue(cache.residentBytes() > before);
        assertEquals(leaf.heapBytes(), cache.residentBytes());

        Leaf small = load();
        NodeCache tight = new NodeCache(small.heapBytes() + 2_000, PAGE);
        tight.put(rootPage, small, small.heapBytes());
        for (int i = 0; i < RECORDS; i++) {
            small.value(i);
            assertTrue(tight.residentBytes() <= small.heapBytes() + 2_000, "resident " + tight.residentBytes());
        }
        assertEquals(0, tight.residentBytes(), "the grown leaf no longer fits and is evicted");
    }

    @Test
    void chargesForAnEvictedOrClearedLeafAreIgnored() {
        NodeCache cache = new NodeCache(1 << 20, PAGE);
        Leaf evicted = load();
        cache.put(rootPage, evicted, evicted.heapBytes());
        cache.put(rootPage, load(), 100);
        long resident = cache.residentBytes();
        evicted.value(3);
        assertEquals(resident, cache.residentBytes());

        Leaf cleared = load();
        cache.put(rootPage + 1, cleared, cleared.heapBytes());
        cache.clear();
        cleared.value(4);
        assertEquals(0, cache.residentBytes());
    }

    @Test
    void checkingTheTreeFindsAPackedLeafWhoseHeaderClaimsPageReferencesItLacks() {
        MemorySegment image = pages.image(rootPage);
        PageHeader header = PageHeader.verify(image, rootPage);
        assertTrue(!header.refersToPages());
        new TreeVerifier(pages).verify(root, EngineSlots.ATOM_SCHEMA);
        image.set(ValueLayout.JAVA_BYTE, 7, (byte) (image.get(ValueLayout.JAVA_BYTE, 7) | 1));
        PageHeader.seal(image, header.payloadLength());
        RuntimeException fault = assertThrows(RuntimeException.class, () -> new TreeVerifier(pages).verify(root, EngineSlots.ATOM_SCHEMA));
        assertTrue(fault.getMessage().contains("disagree on whether it refers to other pages"), fault.getMessage());
    }

    @Test
    void aLengthThatDoesNotMatchTheRecordsIsReportedAsCorruption() {
        MemorySegment image = pages.image(rootPage);
        PageHeader header = PageHeader.verify(image, rootPage);
        ByteCursor in = ByteCursor.over(image).position(PageHeader.SIZE);
        for (int field = 0; field < 5; field++) {
            in.getSignedVarLong();
        }
        in.getLong();
        in.getSignedVarLong();
        for (int key = 1; key < header.slotCount(); key++) {
            in.getVarLong();
        }
        long first = in.position();
        MemorySegment corrupt = MemorySegment.ofArray(image.toArray(ValueLayout.JAVA_BYTE));
        byte length = corrupt.get(ValueLayout.JAVA_BYTE, first);
        assertNotEquals(0, length);
        corrupt.set(ValueLayout.JAVA_BYTE, first, (byte) (length + 1));
        PageHeader.seal(corrupt, header.payloadLength());
        assertThrows(HStoreException.class, () -> NodeCodec.decode(corrupt, rootPage, EngineSlots.ATOM_SCHEMA));
    }
}
