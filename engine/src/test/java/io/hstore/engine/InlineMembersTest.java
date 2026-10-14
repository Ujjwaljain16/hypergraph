// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.catalog.AtomRecord.EdgeRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.feed.CommitEvent;
import io.hstore.engine.maintenance.Compactor;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.TreeVerifier;
import io.hstore.engine.txn.CommitResult;
import io.hstore.engine.txn.CrashPoint;
import io.hstore.engine.txn.Isolation;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.TxnOptions;
import io.hstore.engine.txn.View;
import io.hstore.engine.txn.WalMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineMembersTest {

    private static final long BASE = 1_000;

    @TempDir
    Path directory;

    private static EngineOptions options() {
        return EngineTest.small().withPagesPerSegment(64).withHistoryLimit(50).withCheckpointWalBytes(Long.MAX_VALUE);
    }

    @Test
    void smallEdgesKeepTheirMembersInTheirRecord() {
        Map<Long, List<Long>> expected = new HashMap<>();
        Map<Long, Class<? extends Ref>> shapes = new HashMap<>();
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            for (int size : new int[]{0, 1, 3, 32, 33}) {
                long edge = setEdge(engine, LongStream.range(BASE, BASE + size).boxed().toList());
                expected.put(edge, LongStream.range(BASE, BASE + size).boxed().toList());
                shapes.put(edge, size == 0 ? Ref.Empty.class : size <= Ref.INLINE_MAX_ENTRIES ? Ref.Inline.class : Ref.Stored.class);
            }
            long wide = setEdge(engine, LongStream.range(0, 20).map(i -> i << 40).boxed().toList());
            expected.put(wide, LongStream.range(0, 20).map(i -> i << 40).boxed().toList());
            shapes.put(wide, Ref.Stored.class);
            assertShapes(engine, expected, shapes);
            engine.verifyReferences();
            verifyTrees(engine);
        }
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            assertShapes(engine, expected, shapes);
            engine.verifyReferences();
            verifyTrees(engine);
        }
    }

    @Test
    void orderedEdgesKeepBothTreesInline() {
        List<Long> order = List.of(BASE + 7, BASE + 2, BASE + 5);
        long edge;
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            edge = engine.write(txn -> {
                long created = txn.createEdge(EngineTest.EDGE, EdgeKind.ORDERED);
                order.forEach(member -> txn.append(created, Incidence.of(member)));
                return created;
            });
            EdgeRecord record = record(engine, edge);
            assertInstanceOf(Ref.Inline.class, record.members());
            assertInstanceOf(Ref.Inline.class, record.order());
            engine.write(txn -> {
                txn.insertAt(edge, 1, Incidence.of(BASE + 9));
                txn.removeAt(edge, 0);
                return null;
            });
            assertEquals(List.of(BASE + 9, BASE + 2, BASE + 5), ordered(engine, edge));
            assertEquals(List.of(BASE + 9, BASE + 2, BASE + 5), visited(engine, edge));
            engine.write(txn -> {
                for (int i = 0; i < 40; i++) {
                    txn.insertAt(edge, 0, Incidence.of(BASE + 100 + i));
                }
                return null;
            });
            assertInstanceOf(Ref.Stored.class, record(engine, edge).members());
            assertInstanceOf(Ref.Stored.class, record(engine, edge).order());
            engine.write(txn -> {
                for (int i = 0; i < 40; i++) {
                    txn.removeAt(edge, 0);
                }
                return null;
            });
            assertInstanceOf(Ref.Inline.class, record(engine, edge).members());
            assertInstanceOf(Ref.Inline.class, record(engine, edge).order());
            engine.verifyReferences();
            verifyTrees(engine);
        }
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            assertEquals(List.of(BASE + 9, BASE + 2, BASE + 5), ordered(engine, edge));
            assertEquals(1L, (long) engine.read(snapshot -> snapshot.requireEdge(edge).indexOf(BASE + 2)));
            engine.verifyReferences();
        }
    }

    @Test
    void crossingTheThresholdKeepsReferenceCountsExact() {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            long edge = setEdge(engine, LongStream.range(BASE, BASE + 31).boxed().toList());
            assertInstanceOf(Ref.Inline.class, record(engine, edge).members());
            for (int round = 0; round < 6; round++) {
                engine.write(txn -> {
                    txn.insert(edge, BASE + 31);
                    txn.insert(edge, BASE + 32);
                    return null;
                });
                assertInstanceOf(Ref.Stored.class, record(engine, edge).members());
                engine.verifyReferences();
                engine.write(txn -> {
                    txn.remove(edge, BASE + 31);
                    txn.remove(edge, BASE + 32);
                    return null;
                });
                assertInstanceOf(Ref.Inline.class, record(engine, edge).members());
                engine.verifyReferences();
                if (round % 2 == 1) {
                    engine.checkpoint();
                }
            }
            engine.write(txn -> {
                txn.upsert(edge, BASE, incidence -> incidence.withWeight(2.5));
                return null;
            });
            assertInstanceOf(Ref.Inline.class, record(engine, edge).members());
            assertEquals(2.5, engine.read(snapshot -> snapshot.incidence(edge, BASE)).orElseThrow().weightValue());
            engine.write(txn -> {
                txn.delete(edge);
                return null;
            });
            engine.verifyReferences();
            assertTrue(engine.read(snapshot -> snapshot.atom(edge)).isEmpty());
        }
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            engine.verifyReferences();
        }
    }

    @Test
    void randomSmallEdgeWorkKeepsCountsAndContents() {
        Random random = new Random(55);
        Map<Long, TreeSet<Long>> model = new HashMap<>();
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            for (int i = 0; i < 12; i++) {
                model.put(setEdge(engine, List.of()), new TreeSet<>());
            }
            List<Long> edges = List.copyOf(model.keySet());
            for (int round = 0; round < 150; round++) {
                long edge = edges.get(random.nextInt(edges.size()));
                TreeSet<Long> members = model.get(edge);
                List<Long> added = new ArrayList<>();
                List<Long> removed = new ArrayList<>();
                for (int i = 0; i < 1 + random.nextInt(8); i++) {
                    long member = BASE + random.nextInt(48);
                    if (members.contains(member) && !removed.contains(member) && !added.contains(member)) {
                        removed.add(member);
                    } else if (!members.contains(member) && !added.contains(member)) {
                        added.add(member);
                    }
                }
                engine.write(txn -> {
                    added.forEach(member -> txn.insert(edge, member));
                    removed.forEach(member -> txn.remove(edge, member));
                    return null;
                });
                members.addAll(added);
                removed.forEach(members::remove);
                if (round % 25 == 24) {
                    engine.checkpoint();
                    engine.verifyReferences();
                }
            }
            assertModel(engine, model);
            engine.verifyReferences();
        }
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            assertModel(engine, model);
            engine.verifyReferences();
            verifyTrees(engine);
        }
    }

    static Stream<Arguments> crashMatrix() {
        return Stream.of(WalMode.values()).flatMap(mode -> EnumSet.range(CrashPoint.PAGE, CrashPoint.CATALOG_PUBLISH).stream()
                .map(point -> Arguments.of(mode, point)));
    }

    @ParameterizedTest
    @MethodSource("crashMatrix")
    void aCrashLeavesSmallEdgesBeforeOrAfterTheirCommit(WalMode mode, CrashPoint point) {
        AtomicBoolean armed = new AtomicBoolean(false);
        EngineOptions options = options().withWalMode(mode).withFaults(reached -> {
            if (reached == point && armed.compareAndSet(true, false)) {
                throw new CrashPoint.SimulatedCrash(point);
            }
        });
        StorageEngine engine = StorageEngine.open(directory, options);
        long growing = setEdge(engine, LongStream.range(BASE, BASE + 3).boxed().toList());
        long shrinking = setEdge(engine, LongStream.range(BASE, BASE + 40).boxed().toList());
        long fresh = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
        armed.set(true);
        Transaction doomed = engine.begin();
        LongStream.range(BASE + 3, BASE + 40).forEach(member -> doomed.insert(growing, member));
        LongStream.range(BASE + 3, BASE + 40).forEach(member -> doomed.remove(shrinking, member));
        LongStream.range(BASE, BASE + 5).forEach(member -> doomed.insert(fresh, member));
        assertThrows(CrashPoint.SimulatedCrash.class, doomed::commit);
        engine.halt();
        boolean committed = point.ordinal() >= CrashPoint.COMMIT_APPEND.ordinal();
        try (StorageEngine recovered = StorageEngine.open(directory, options().withWalMode(mode))) {
            recovered.verifyReferences();
            verifyTrees(recovered);
            assertEquals(committed ? 40L : 3L, (long) recovered.read(snapshot -> snapshot.requireEdge(growing).size()));
            assertEquals(committed ? 3L : 40L, (long) recovered.read(snapshot -> snapshot.requireEdge(shrinking).size()));
            assertEquals(committed ? 5L : 0L, (long) recovered.read(snapshot -> snapshot.requireEdge(fresh).size()));
            recovered.write(txn -> {
                txn.insert(shrinking, BASE + 500);
                return null;
            });
        }
        try (StorageEngine reopened = StorageEngine.open(directory, options().withWalMode(mode))) {
            assertTrue((boolean) reopened.read(snapshot -> snapshot.contains(shrinking, BASE + 500)));
            reopened.verifyReferences();
        }
    }

    @ParameterizedTest
    @EnumSource(WalMode.class)
    void inlineEdgesCommittedAfterTheCheckpointComeBackFromTheLog(WalMode mode) {
        EngineOptions options = options().withWalMode(mode);
        List<Long> edges = new ArrayList<>();
        StorageEngine engine = StorageEngine.open(directory, options);
        engine.checkpoint();
        for (int i = 0; i < 20; i++) {
            int size = i % 5 == 4 ? 40 : 1 + i % 5;
            edges.add(setEdge(engine, LongStream.range(BASE + i, BASE + i + size).boxed().toList()));
        }
        long reshaped = edges.getFirst();
        engine.write(txn -> {
            LongStream.range(BASE + 100, BASE + 140).forEach(member -> txn.insert(reshaped, member));
            return null;
        });
        engine.write(txn -> {
            LongStream.range(BASE + 100, BASE + 140).forEach(member -> txn.remove(reshaped, member));
            return null;
        });
        engine.halt();
        try (StorageEngine recovered = StorageEngine.open(directory, options)) {
            assertTrue(recovered.recovery().replayedCommits() >= 22);
            for (int i = 0; i < edges.size(); i++) {
                int size = i % 5 == 4 ? 40 : 1 + i % 5;
                assertEquals(LongStream.range(BASE + i, BASE + i + size).boxed().toList(), members(recovered, edges.get(i)));
            }
            assertInstanceOf(Ref.Inline.class, record(recovered, reshaped).members());
            recovered.verifyReferences();
            verifyTrees(recovered);
        }
    }

    @Test
    void compactionCarriesInlineEdgesAlong() {
        EngineOptions options = options().withHistoryLimit(2);
        Map<Long, TreeSet<Long>> model = new HashMap<>();
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            for (int i = 0; i < 30; i++) {
                List<Long> members = LongStream.range(BASE + i, BASE + i + 1 + i % 4).boxed().toList();
                model.put(setEdge(engine, members), new TreeSet<>(members));
            }
            List<Long> edges = List.copyOf(model.keySet());
            for (int round = 0; round < 80; round++) {
                long edge = edges.get(round % edges.size());
                long member = BASE + 200 + round;
                engine.write(txn -> {
                    txn.insert(edge, member);
                    return null;
                });
                model.get(edge).add(member);
                if (model.get(edge).size() > 4) {
                    long first = model.get(edge).first();
                    engine.write(txn -> {
                        txn.remove(edge, first);
                        return null;
                    });
                    model.get(edge).remove(first);
                }
                engine.checkpoint();
            }
            Compactor.Report report = engine.compact();
            assertFalse(report.compacted().isEmpty());
            assertModel(engine, model);
            engine.verifyReferences();
        }
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            assertModel(engine, model);
            engine.verifyReferences();
            verifyTrees(engine);
        }
    }

    @Test
    void historyAndTheFeedFollowAnEdgeAcrossTheThreshold() {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            long start = engine.transactions().current().id();
            long edge = setEdge(engine, LongStream.range(BASE, BASE + 3).boxed().toList());
            long small = engine.stats().generation();
            engine.write(txn -> {
                LongStream.range(BASE + 3, BASE + 40).forEach(member -> txn.insert(edge, member));
                return null;
            });
            long large = engine.stats().generation();
            engine.write(txn -> {
                LongStream.range(BASE + 1, BASE + 39).forEach(member -> txn.remove(edge, member));
                return null;
            });
            try (Snapshot then = engine.snapshotAt(small, 0); Snapshot later = engine.snapshotAt(large, 0)) {
                assertEquals(LongStream.range(BASE, BASE + 3).boxed().toList(), members(then, edge));
                assertEquals(LongStream.range(BASE, BASE + 40).boxed().toList(), members(later, edge));
            }
            assertEquals(List.of(BASE, BASE + 39), members(engine, edge));
            assertInstanceOf(Ref.Inline.class, record(engine, edge).members());
            TreeSet<Long> replayed = new TreeSet<>();
            for (CommitEvent event : engine.feed().replay(start).toList()) {
                for (MemberChange change : event.members()) {
                    if (change.edge() == edge) {
                        switch (change) {
                            case MemberChange.Added added -> assertTrue(replayed.add(added.member()));
                            case MemberChange.Removed removed -> assertTrue(replayed.remove(removed.member()));
                            case MemberChange.Updated _ -> {
                            }
                        }
                    }
                }
            }
            assertEquals(new TreeSet<>(List.of(BASE, BASE + 39)), replayed);
        }
    }

    @Test
    void inlineEdgesAreComparedByContentWhenCommitting() {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            long edge = setEdge(engine, List.of(BASE, BASE + 1));
            long other = setEdge(engine, List.of());

            Transaction strict = engine.begin(TxnOptions.defaults().withIsolation(Isolation.SERIALIZABLE));
            assertFalse(strict.contains(edge, BASE + 5));
            strict.insert(other, BASE + 5);
            engine.write(txn -> {
                txn.insert(edge, BASE + 7);
                return null;
            });
            engine.write(txn -> {
                txn.remove(edge, BASE + 7);
                return null;
            });
            strict.commit();

            Transaction reader = engine.begin(TxnOptions.defaults().withIsolation(Isolation.SERIALIZABLE));
            assertFalse(reader.contains(edge, BASE + 5));
            reader.insert(other, BASE + 6);
            engine.write(txn -> {
                txn.insert(edge, BASE + 5);
                return null;
            });
            assertThrows(HStoreException.Conflict.class, reader::commit);

            Transaction first = engine.begin();
            Transaction second = engine.begin();
            first.insert(edge, BASE + 20);
            second.insert(edge, BASE + 21);
            assertEquals(CommitResult.Outcome.COMMITTED, first.commit().outcome());
            assertEquals(CommitResult.Outcome.REBASED, second.commit().outcome());

            Transaction weighs = engine.begin();
            Transaction drops = engine.begin();
            weighs.upsert(edge, BASE, incidence -> incidence.withWeight(4.0));
            drops.remove(edge, BASE);
            weighs.commit();
            assertThrows(HStoreException.Conflict.class, drops::commit);

            assertEquals(List.of(BASE, BASE + 1, BASE + 5, BASE + 20, BASE + 21), members(engine, edge));
            assertInstanceOf(Ref.Inline.class, record(engine, edge).members());
            engine.verifyReferences();
        }
    }

    @Test
    void inlineTreesNeedNoPagesOfTheirOwn() {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            Transaction txn = engine.begin(TxnOptions.defaults().withMaxPages(60));
            for (int i = 0; i < 100; i++) {
                long edge = txn.createEdge(EngineTest.EDGE, EdgeKind.SET);
                for (int m = 0; m < 3; m++) {
                    txn.insert(edge, BASE + i + m);
                }
            }
            CommitResult result = txn.commit();
            assertTrue(result.pagesWritten() <= 60, result.pagesWritten() + " pages");
            engine.verifyReferences();
        }
    }

    private static long setEdge(StorageEngine engine, List<Long> members) {
        return engine.write(txn -> {
            long edge = txn.createEdge(EngineTest.EDGE, EdgeKind.SET);
            members.forEach(member -> txn.insert(edge, member));
            return edge;
        });
    }

    private static EdgeRecord record(StorageEngine engine, long edge) {
        engine.checkpoint();
        return engine.read(snapshot -> record(snapshot, edge));
    }

    private static EdgeRecord record(View view, long edge) {
        return (EdgeRecord) view.atom(edge).orElseThrow();
    }

    private static List<Long> members(StorageEngine engine, long edge) {
        return engine.read(snapshot -> members(snapshot, edge));
    }

    private static List<Long> members(View view, long edge) {
        return view.requireEdge(edge).memberIds().boxed().toList();
    }

    private static List<Long> ordered(StorageEngine engine, long edge) {
        return engine.read(snapshot -> snapshot.requireEdge(edge).stream().map(Incidence::member).toList());
    }

    private static List<Long> visited(StorageEngine engine, long edge) {
        List<Long> members = new ArrayList<>();
        engine.read(snapshot -> {
            snapshot.requireEdge(edge).forEachMemberId(members::add);
            return null;
        });
        return members;
    }

    private static void assertShapes(StorageEngine engine, Map<Long, List<Long>> expected, Map<Long, Class<? extends Ref>> shapes) {
        expected.forEach((edge, members) -> {
            assertEquals(members, members(engine, edge));
            assertInstanceOf(shapes.get(edge), record(engine, edge).members(), "edge of " + members.size() + " members");
            assertEquals(members.size(), (long) engine.read(snapshot -> snapshot.cardinality(edge)));
            members.forEach(member -> assertTrue((boolean) engine.read(snapshot -> snapshot.contains(edge, member))));
        });
    }

    private static void assertModel(StorageEngine engine, Map<Long, TreeSet<Long>> model) {
        model.forEach((edge, members) -> assertEquals(List.copyOf(members), members(engine, edge)));
    }

    private static void verifyTrees(StorageEngine engine) {
        try (Snapshot snapshot = engine.snapshot()) {
            TreeVerifier.Report report = new TreeVerifier(engine.source()).verify(snapshot.roots().get(EngineSlots.CATALOG), EngineSlots.ATOM_SCHEMA);
            assertTrue(report.entries() > 0);
        }
    }
}
