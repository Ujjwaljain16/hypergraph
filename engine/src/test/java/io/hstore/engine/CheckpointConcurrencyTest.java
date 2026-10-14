// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.txn.CrashPoint;
import io.hstore.engine.txn.Durability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointConcurrencyTest {

    private static final int EDGES = 24;

    @TempDir
    Path directory;

    private static EngineOptions options() {
        return EngineTest.small().withCheckpointWalBytes(Long.MAX_VALUE).withMaintenanceInterval(Duration.ofHours(1));
    }

    private static long[] edges(StorageEngine engine) {
        return engine.write(txn -> LongStream.range(0, EDGES).map(_ -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET)).toArray());
    }

    private static void insert(StorageEngine engine, long edge, long member) {
        engine.write(txn -> {
            txn.insert(edge, member);
            return null;
        });
    }

    @ParameterizedTest
    @EnumSource(value = CrashPoint.class, names = {"CHECKPOINT_REBIND", "WAL_FORCE"})
    void aCommitFinishesWhileTheCheckpointIsAtThisStep(CrashPoint point) throws Exception {
        AtomicReference<StorageEngine> opened = new AtomicReference<>();
        AtomicReference<Object> outcome = new AtomicReference<>();
        AtomicBoolean armed = new AtomicBoolean();
        Thread test = Thread.currentThread();
        long[] edges = new long[1];
        EngineOptions options = options().withDurability(Durability.ASYNC).withFaults(reached -> {
            if (reached == point && Thread.currentThread() == test && armed.compareAndSet(true, false)) {
                CompletableFuture<Void> commit = CompletableFuture.runAsync(() -> insert(opened.get(), edges[0], 7_777));
                try {
                    commit.get(5, TimeUnit.SECONDS);
                    outcome.set("committed");
                } catch (TimeoutException e) {
                    outcome.set("the commit waited for the checkpoint");
                } catch (InterruptedException | ExecutionException e) {
                    outcome.set(e);
                }
            }
        });
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            opened.set(engine);
            edges[0] = edges(engine)[0];
            for (long member = 0; member < 50; member++) {
                insert(engine, edges[0], member);
            }
            armed.set(true);
            engine.checkpoint();
            assertEquals("committed", outcome.get());
            assertEquals(51L, (long) engine.read(snapshot -> snapshot.cardinality(edges[0])));
            engine.checkpoint();
            engine.verifyReferences();
        }
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            boolean kept = engine.read(snapshot -> snapshot.requireEdge(edges[0]).memberIds().anyMatch(member -> member == 7_777));
            assertTrue(kept);
            engine.verifyReferences();
        }
    }

    @ParameterizedTest
    @EnumSource(value = CrashPoint.class, names = {"CHECKPOINT_CATALOG", "CHECKPOINT_REBIND", "WAL_FORCE", "CHECKPOINT_JOURNAL"})
    void aCrashAtThisCheckpointStepLosesNoAcknowledgedCommit(CrashPoint point) throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        Thread test = Thread.currentThread();
        EngineOptions options = options().withFaults(reached -> {
            if (reached == point && Thread.currentThread() == test && armed.compareAndSet(true, false)) {
                throw new CrashPoint.SimulatedCrash(point);
            }
        });
        StorageEngine engine = StorageEngine.open(directory, options);
        long[] edges = edges(engine);
        Map<Long, Set<Long>> acknowledged = new ConcurrentHashMap<>();
        AtomicBoolean writing = new AtomicBoolean(true);
        List<Thread> writers = new ArrayList<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        Set<Long> inFlight = ConcurrentHashMap.newKeySet();
        for (int w = 0; w < 3; w++) {
            long first = (w + 1) * 1_000_000L;
            writers.add(Thread.ofPlatform().start(() -> {
                for (long member = first; writing.get(); member++) {
                    long edge = edges[(int) (member % EDGES)];
                    inFlight.add(member);
                    try {
                        insert(engine, edge, member);
                    } catch (Throwable e) {
                        failures.add(e);
                        return;
                    }
                    acknowledged.computeIfAbsent(edge, _ -> ConcurrentHashMap.newKeySet()).add(member);
                    inFlight.remove(member);
                }
            }));
        }
        try {
            for (int i = 0; i < 10; i++) {
                engine.checkpoint();
            }
            armed.set(true);
            assertThrows(CrashPoint.SimulatedCrash.class, engine::checkpoint);
        } finally {
            writing.set(false);
            for (Thread writer : writers) {
                writer.join();
            }
        }
        failures.forEach(failure -> assertTrue(point == CrashPoint.WAL_FORCE
                && (failure instanceof CrashPoint.SimulatedCrash || failure instanceof HStoreException), "writer failed: " + failure));
        engine.halt();
        Map<Long, Set<Long>> first = null;
        for (int round = 0; round < 2; round++) {
            try (StorageEngine recovered = StorageEngine.open(directory, options())) {
                Map<Long, Set<Long>> found = new HashMap<>();
                for (long edge : edges) {
                    Set<Long> members = recovered.read(snapshot -> snapshot.requireEdge(edge).memberIds().boxed().collect(Collectors.toSet()));
                    Set<Long> expected = acknowledged.getOrDefault(edge, Set.of());
                    assertTrue(members.containsAll(expected), "edge " + edge + " lost acknowledged members after reopen " + round);
                    members.stream().filter(member -> !expected.contains(member)).forEach(member ->
                            assertTrue(inFlight.contains(member), "edge " + edge + " gained member " + member + " that was never written"));
                    found.put(edge, members);
                }
                if (first == null) {
                    first = found;
                } else {
                    assertEquals(first, found);
                }
                recovered.verifyReferences();
            }
        }
    }

    @Test
    void writersCommittingThroughBackToBackCheckpointsKeepEveryCommit() throws Exception {
        EngineOptions options = options().withDurability(Durability.ASYNC);
        StorageEngine engine = StorageEngine.open(directory, options);
        long[] edges = edges(engine);
        Map<Long, Set<Long>> acknowledged = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<String> problems = new ConcurrentLinkedQueue<>();
        AtomicBoolean writing = new AtomicBoolean(true);
        List<Thread> writers = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            long first = (w + 1) * 1_000_000L;
            writers.add(Thread.ofPlatform().start(() -> {
                for (long member = first; writing.get() && member < first + 2_000; member++) {
                    long edge = edges[(int) (member % EDGES)];
                    long added = member;
                    insert(engine, edge, added);
                    acknowledged.computeIfAbsent(edge, _ -> ConcurrentHashMap.newKeySet()).add(added);
                    if (!engine.read(snapshot -> snapshot.requireEdge(edge).memberIds().anyMatch(m -> m == added))) {
                        problems.add("member " + added + " of edge " + edge + " vanished right after its commit");
                    }
                }
            }));
        }
        int checkpoints = 0;
        try {
            while (writers.stream().anyMatch(Thread::isAlive)) {
                engine.checkpoint();
                checkpoints++;
            }
        } finally {
            writing.set(false);
            for (Thread writer : writers) {
                writer.join();
            }
        }
        assertTrue(checkpoints > 5, "only " + checkpoints + " checkpoints overlapped the writers");
        assertTrue(problems.isEmpty(), problems.stream().limit(5).toList().toString());
        assertMembers(engine, edges, acknowledged);
        engine.checkpoint();
        engine.verifyReferences();
        engine.verifyChangeLog();
        engine.close();
        try (StorageEngine reopened = StorageEngine.open(directory, options)) {
            assertMembers(reopened, edges, acknowledged);
            reopened.verifyReferences();
        }
    }

    private static void assertMembers(StorageEngine engine, long[] edges, Map<Long, Set<Long>> acknowledged) {
        for (long edge : edges) {
            Set<Long> members = engine.read(snapshot -> snapshot.requireEdge(edge).memberIds().boxed().collect(Collectors.toSet()));
            assertEquals(acknowledged.getOrDefault(edge, Set.of()), members, "edge " + edge);
        }
    }
}
