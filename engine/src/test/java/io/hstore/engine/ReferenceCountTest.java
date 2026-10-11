package io.hstore.engine;

import io.hstore.engine.catalog.Branch;
import io.hstore.engine.maintenance.ReferenceCounter;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.txn.CommitResult;
import io.hstore.engine.txn.CrashPoint;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.TxnOptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceCountTest {

    @TempDir
    Path directory;

    private static EngineOptions options() {
        return EngineTest.small().withPagesPerSegment(64).withHistoryLimit(3).withCheckpointWalBytes(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3})
    void countsMatchAFullWalkThroughRandomWork(long seed) {
        Random random = new Random(seed);
        Map<Long, TreeSet<Long>> model = new HashMap<>();
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            for (int round = 0; round < 120; round++) {
                step(engine, random, model);
                if (round % 15 == 14) {
                    engine.checkpoint();
                    engine.verifyReferences();
                }
            }
            engine.verifyReferences();
            assertModel(engine, model);
        }
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            engine.verifyReferences();
            assertModel(engine, model);
        }
    }

    private static void step(StorageEngine engine, Random random, Map<Long, TreeSet<Long>> model) {
        int choice = random.nextInt(10);
        List<Long> edges = new ArrayList<>(model.keySet());
        if (choice == 0 || edges.isEmpty()) {
            long edge = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
            model.put(edge, new TreeSet<>());
        } else if (choice == 1 && edges.size() > 2) {
            long edge = edges.get(random.nextInt(edges.size()));
            engine.write(txn -> {
                txn.delete(edge);
                return null;
            });
            model.remove(edge);
        } else if (choice == 2) {
            long edge = edges.get(random.nextInt(edges.size()));
            long start = 1_000_000L + random.nextInt(1_000_000);
            List<Incidence> members = LongStream.range(start, start + 2_000).mapToObj(Incidence::of).toList();
            long target = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
            engine.write(txn -> {
                txn.load(target, members);
                return null;
            });
            model.put(target, new TreeSet<>(members.stream().map(Incidence::member).toList()));
        } else if (choice == 3) {
            long target = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
            model.put(target, new TreeSet<>());
            try (Transaction abandoned = engine.begin()) {
                abandoned.load(target, LongStream.range(5_000_000, 5_002_000).mapToObj(Incidence::of).toList());
            }
        } else if (choice == 4) {
            Branch branch = engine.createBranch("b" + engine.stats().generation(), Branch.MAIN);
            long edge = edges.get(random.nextInt(edges.size()));
            for (int i = 0; i < 3; i++) {
                long member = 9_000_000L + random.nextInt(1_000_000);
                engine.write(TxnOptions.defaults().onBranch(branch.id()), txn -> {
                    txn.insert(edge, member);
                    return null;
                });
            }
            if (random.nextBoolean()) {
                engine.dropBranch(branch.id());
            }
        } else {
            long edge = edges.get(random.nextInt(edges.size()));
            TreeSet<Long> members = model.get(edge);
            List<Long> added = new ArrayList<>();
            List<Long> removed = new ArrayList<>();
            for (int i = 0; i < 1 + random.nextInt(60); i++) {
                long member = random.nextInt(20_000);
                if (members.contains(member) || added.contains(member)) {
                    if (!added.contains(member) && !removed.contains(member)) {
                        removed.add(member);
                    }
                } else if (!removed.contains(member)) {
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
        }
    }

    private static void assertModel(StorageEngine engine, Map<Long, TreeSet<Long>> model) {
        engine.read(snapshot -> {
            model.forEach((edge, members) ->
                    assertEquals(List.copyOf(members), snapshot.requireEdge(edge).stream().map(Incidence::member).toList(), "edge " + edge));
            return null;
        });
    }

    @Test
    void pagesAreReleasedOnlyOnceNoReaderCanReachThem() {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            long edge = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
            engine.write(txn -> {
                LongStream.range(0, 3_000).forEach(member -> txn.insert(edge, member));
                return null;
            });
            long full;
            try (Snapshot reader = engine.snapshot()) {
                for (int round = 0; round < 30; round++) {
                    long start = round * 100L;
                    engine.write(txn -> {
                        LongStream.range(start, start + 100).forEach(member -> txn.remove(edge, member));
                        return null;
                    });
                }
                engine.checkpoint();
                ReferenceCounter.Verification held = engine.verifyReferences();
                assertTrue(held.waitingPages() > 0, "pages the reader can reach must wait");
                assertEquals(3_000L, reader.requireEdge(edge).size());
                full = live(engine);
            }
            for (int i = 0; i < 4; i++) {
                engine.write(txn -> txn.createNode(EngineTest.NODE, "after-" + txn.id()));
            }
            engine.checkpoint();
            ReferenceCounter.Verification released = engine.verifyReferences();
            assertTrue(live(engine) < full, "live bytes " + live(engine) + " should fall below " + full);
            assertEquals(0L, (long) engine.read(snapshot -> snapshot.requireEdge(edge).size()));
            assertTrue(released.waitingPages() < 10, "waiting " + released.waitingPages());
        }
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            engine.verifyReferences();
        }
    }

    @Test
    void anAbortedBulkLoadGivesItsPagesBack() {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            long edge = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
            long before = live(engine);
            try (Transaction abandoned = engine.begin()) {
                abandoned.load(edge, spread(20_000));
                assertTrue(live(engine) > before + 10_000, "the load should have spilled pages");
            }
            for (int i = 0; i < 4; i++) {
                engine.write(txn -> txn.createNode(EngineTest.NODE, "after-" + txn.id()));
            }
            engine.checkpoint();
            assertTrue(live(engine) < before + 10_000, "live bytes " + live(engine) + " from " + before);
            engine.verifyReferences();
        }
    }

    @Test
    void pagesALoadSpillsAndTheSameTransactionReplacesAreGivenBack() {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            long edge = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
            long before = live(engine);
            engine.write(txn -> {
                txn.load(edge, spread(20_000));
                txn.delete(edge);
                return null;
            });
            for (int i = 0; i < 4; i++) {
                engine.write(txn -> txn.createNode(EngineTest.NODE, "after-" + txn.id()));
            }
            engine.checkpoint();
            assertTrue(live(engine) < before + 10_000, "live bytes " + live(engine) + " from " + before);
            engine.verifyReferences();
        }
    }

    @Test
    void aDuplicateRequestGivesBackThePagesItsLoadSpilled() {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            long edge = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
            TxnOptions request = TxnOptions.defaults().withRequestId("load-once");
            engine.write(request, txn -> {
                txn.insert(edge, 1);
                return null;
            });
            long before = live(engine);
            long other = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
            try (Transaction repeated = engine.begin(request)) {
                repeated.load(other, spread(20_000));
                assertEquals(CommitResult.Outcome.DUPLICATE, repeated.commit().outcome());
            }
            for (int i = 0; i < 4; i++) {
                engine.write(txn -> txn.createNode(EngineTest.NODE, "after-" + txn.id()));
            }
            engine.checkpoint();
            assertTrue(live(engine) < before + 10_000, "live bytes " + live(engine) + " from " + before);
            engine.verifyReferences();
        }
    }

    @Test
    void pagesSpilledByATransactionCutShortByACrashAreReleasedOnOpen() {
        long before;
        StorageEngine engine = StorageEngine.open(directory, options());
        long edge = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
        engine.checkpoint();
        before = live(engine);
        Transaction inFlight = engine.begin();
        inFlight.load(edge, spread(20_000));
        engine.halt();
        try (StorageEngine reopened = StorageEngine.open(directory, options())) {
            assertTrue(live(reopened) < before + 10_000, "live bytes " + live(reopened) + " from " + before);
            reopened.verifyReferences();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void countsSurviveACrash(boolean duringCheckpoint) {
        AtomicBoolean armed = new AtomicBoolean();
        EngineOptions options = options().withFaults(point -> {
            if (point == CrashPoint.CHECKPOINT_JOURNAL && armed.compareAndSet(true, false)) {
                throw new CrashPoint.SimulatedCrash(point);
            }
        });
        Random random = new Random(11);
        Map<Long, TreeSet<Long>> model = new HashMap<>();
        StorageEngine engine = StorageEngine.open(directory, options);
        for (int round = 0; round < 40; round++) {
            step(engine, random, model);
        }
        engine.checkpoint();
        for (int round = 0; round < 40; round++) {
            step(engine, random, model);
        }
        if (duringCheckpoint) {
            armed.set(true);
            assertThrows(CrashPoint.SimulatedCrash.class, engine::checkpoint);
        }
        engine.halt();
        try (StorageEngine recovered = StorageEngine.open(directory, options())) {
            recovered.verifyReferences();
            assertModel(recovered, model);
        }
    }

    @Test
    void countsMarkedForRebuildAreRebuiltOnOpen() throws Exception {
        Random random = new Random(5);
        Map<Long, TreeSet<Long>> model = new HashMap<>();
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            for (int round = 0; round < 60; round++) {
                step(engine, random, model);
            }
        }
        Path counts = directory.resolve("data").resolve("segments").resolve("pages.ref");
        try (FileChannel file = FileChannel.open(counts, StandardOpenOption.WRITE)) {
            file.write(ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(0, 1), 8);
            file.write(ByteBuffer.allocate(Long.BYTES * 64), 64);
        }
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            engine.verifyReferences();
            assertModel(engine, model);
            for (int round = 0; round < 20; round++) {
                step(engine, random, model);
            }
            engine.checkpoint();
            engine.verifyReferences();
        }
    }

    private static List<Incidence> spread(int count) {
        return LongStream.range(0, count).map(i -> i * 7_919).mapToObj(Incidence::of).toList();
    }

    private static long live(StorageEngine engine) {
        return engine.liveness().values().stream().mapToLong(Long::longValue).sum();
    }
}
