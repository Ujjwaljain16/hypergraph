// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.txn.CommitResult;
import io.hstore.engine.txn.CrashPoint;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.View;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredWritesTest {

    @TempDir
    Path directory;

    private static EngineOptions options() {
        return EngineTest.small().withCheckpointWalBytes(Long.MAX_VALUE).withMaintenanceInterval(Duration.ofHours(1));
    }

    @Test
    void smallCommitsWriteNoPagesAndACheckpointWritesEachNodeOnce() {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            long edge = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
            engine.checkpoint();
            long before = engine.stats().pagesWritten();
            for (int i = 0; i < 300; i++) {
                long member = i;
                Transaction txn = engine.begin();
                txn.insert(edge, member);
                CommitResult result = txn.commit();
                assertEquals(0, result.pagesWritten(), "commit " + i);
            }
            assertEquals(before, engine.stats().pagesWritten());
            engine.checkpoint();
            long written = engine.stats().pagesWritten() - before;
            assertTrue(written > 0 && written < 60, "a checkpoint after 300 single inserts wrote " + written + " pages");
            assertEquals(300L, (long) engine.read(snapshot -> snapshot.cardinality(edge)));
            engine.transactions().current().branches().values().forEach(branch -> branch.roots().roots().values()
                    .forEach(root -> assertTrue(root instanceof Ref.Stored, "a checkpointed root is still " + root)));
            engine.verifyReferences();
        }
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            long only = engine.read(DeferredWritesTest::onlyEdge);
            assertEquals(300L, (long) engine.read(snapshot -> snapshot.cardinality(only)));
            engine.verifyReferences();
        }
    }

    @Test
    void aRestartKeepsTheCheckpointedGenerationAndTheReplayedOnes() {
        long edge;
        long checkpointed;
        long replayed;
        StorageEngine engine = StorageEngine.open(directory, options());
        edge = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
        engine.write(txn -> {
            txn.insert(edge, 1);
            return null;
        });
        checkpointed = engine.checkpoint().generation();
        engine.write(txn -> {
            txn.insert(edge, 2);
            return null;
        });
        replayed = engine.stats().generation();
        engine.write(txn -> {
            txn.insert(edge, 3);
            return null;
        });
        engine.halt();
        try (StorageEngine reopened = StorageEngine.open(directory, options());
             Snapshot atCheckpoint = reopened.snapshotAt(checkpointed, 0);
             Snapshot atReplayed = reopened.snapshotAt(replayed, 0)) {
            assertEquals(List.of(1L), atCheckpoint.requireEdge(edge).memberIds().boxed().toList());
            assertEquals(List.of(1L, 2L), atReplayed.requireEdge(edge).memberIds().boxed().toList());
            assertEquals(3L, (long) reopened.read(snapshot -> snapshot.cardinality(edge)));
        }
    }

    @Test
    void theNextCheckpointKeepsThePagesABulkLoadWrote() {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            long edge = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
            engine.checkpoint();
            long before = engine.stats().pagesWritten();
            engine.write(txn -> {
                txn.load(edge, LongStream.range(0, 20_000).mapToObj(Incidence::of).toList());
                return null;
            });
            long loaded = engine.stats().pagesWritten() - before;
            assertTrue(loaded > 100, "the load wrote only " + loaded + " pages");
            engine.checkpoint();
            long rewritten = engine.stats().pagesWritten() - before - loaded;
            assertTrue(rewritten < 10, "the checkpoint wrote " + rewritten + " pages after a load of " + loaded);
            engine.verifyReferences();
        }
    }

    @ParameterizedTest
    @EnumSource(value = CrashPoint.class, names = {"CHECKPOINT_PAGES", "CHECKPOINT_CATALOG", "CHECKPOINT_JOURNAL"})
    void aCheckpointCutShortWhileCommitsContinueLosesNoAcknowledgedCommit(CrashPoint point) throws Exception {
        AtomicBoolean armed = new AtomicBoolean(false);
        EngineOptions options = options().withFaults(reached -> {
            if (reached == point && armed.compareAndSet(true, false)) {
                throw new CrashPoint.SimulatedCrash(point);
            }
        });
        ConcurrentLinkedQueue<Long> acknowledged = new ConcurrentLinkedQueue<>();
        long edge;
        StorageEngine engine = StorageEngine.open(directory, options);
        edge = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
        for (long member = 0; member < 200; member++) {
            long added = member;
            engine.write(txn -> {
                txn.insert(edge, added);
                return null;
            });
            acknowledged.add(added);
        }
        engine.checkpoint();
        AtomicBoolean writing = new AtomicBoolean(true);
        try (ExecutorService writer = Executors.newSingleThreadExecutor()) {
            Future<?> writes = writer.submit(() -> {
                for (long member = 1_000; writing.get(); member++) {
                    long added = member;
                    try {
                        engine.write(txn -> {
                            txn.insert(edge, added);
                            return null;
                        });
                    } catch (RuntimeException stopped) {
                        return;
                    }
                    acknowledged.add(added);
                }
            });
            try {
                for (int i = 0; i < 20; i++) {
                    engine.checkpoint();
                }
                armed.set(true);
                assertThrows(CrashPoint.SimulatedCrash.class, engine::checkpoint);
            } finally {
                writing.set(false);
                writes.get();
            }
        }
        engine.halt();
        try (StorageEngine recovered = StorageEngine.open(directory, options())) {
            List<Long> members = recovered.read(snapshot -> snapshot.requireEdge(edge).memberIds().boxed().toList());
            assertTrue(members.containsAll(acknowledged), "lost " + acknowledged.stream().filter(m -> !members.contains(m)).limit(5).toList());
            assertEquals(acknowledged.size(), members.size());
            recovered.verifyReferences();
        }
        try (StorageEngine reopened = StorageEngine.open(directory, options())) {
            assertEquals(acknowledged.size(), (long) reopened.read(snapshot -> snapshot.cardinality(edge)));
            reopened.verifyReferences();
        }
    }

    @Test
    void aFullWriteBufferMakesCommitsWaitForACheckpoint() {
        EngineOptions options = options().withWriteBufferBytes(256 * 1024);
        long edge;
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            edge = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
            long started = System.nanoTime();
            for (long member = 0; member < 3_000; member++) {
                long added = member;
                engine.write(txn -> {
                    txn.insert(edge, added);
                    return null;
                });
                assertTrue(engine.transactions().unwrittenBytes() < 2 * options.writeBufferBytes(),
                        "unwritten bytes " + engine.transactions().unwrittenBytes());
            }
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(60).toNanos());
            assertTrue(engine.stats().pagesWritten() > 0, "the full buffer never triggered a checkpoint");
        }
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            assertEquals(3_000L, (long) engine.read(snapshot -> snapshot.cardinality(edge)));
            engine.verifyReferences();
        }
    }

    private static long onlyEdge(View view) {
        return view.atomsOfType(0, EngineTest.EDGE).findFirst().orElseThrow();
    }
}
