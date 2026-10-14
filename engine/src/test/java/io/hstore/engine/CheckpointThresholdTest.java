// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.txn.CrashPoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointThresholdTest {

    @TempDir
    Path directory;

    @Test
    void smallStoresGetTheFloorAndLargeStoresTheCeiling() {
        assertEquals(2L << 20, CheckpointThreshold.automatic(0));
        assertEquals(2L << 20, CheckpointThreshold.automatic(8L << 20));
        assertEquals(3L << 20, CheckpointThreshold.automatic(12L << 20));
        assertEquals(4L << 20, CheckpointThreshold.automatic(16L << 20));
        assertEquals(8L << 20, CheckpointThreshold.automatic(32L << 20));
        assertEquals(128L << 20, CheckpointThreshold.automatic(512L << 20));
        assertEquals(256L << 20, CheckpointThreshold.automatic(1L << 30));
        assertEquals(256L << 20, CheckpointThreshold.automatic(100L << 30));
    }

    @Test
    void aStoredValueWinsOverTheConfigurationWhichWinsOverTheRule() {
        assertEquals(new CheckpointThreshold(64L << 20, CheckpointThreshold.Source.DATABASE),
                CheckpointThreshold.resolve(OptionalLong.of(64L << 20), 128L << 20, 1L << 30));
        assertEquals(new CheckpointThreshold(128L << 20, CheckpointThreshold.Source.CONFIGURATION),
                CheckpointThreshold.resolve(OptionalLong.empty(), 128L << 20, 1L << 30));
        assertEquals(new CheckpointThreshold(10L << 20, CheckpointThreshold.Source.AUTOMATIC),
                CheckpointThreshold.resolve(OptionalLong.empty(), EngineOptions.AUTOMATIC_CHECKPOINTS, 40L << 20));
        assertEquals(EngineOptions.AUTOMATIC_CHECKPOINTS, EngineOptions.defaults().checkpointWalBytes());
        assertEquals(EngineOptions.AUTOMATIC_CHECKPOINTS, EngineOptions.defaults().withCheckpointWalBytes(1 << 20).withAutomaticCheckpoints().checkpointWalBytes());
    }

    @Test
    void aSmallStoreWithDefaultOptionsCheckpointsOnceItsLogPassesTheFloor() throws InterruptedException {
        EngineOptions options = EngineTest.small().withWriteBufferBytes(1L << 30).withMaintenanceInterval(Duration.ofMillis(50));
        AtomicInteger checkpoints = new AtomicInteger();
        int commits = 0;
        StorageEngine engine = StorageEngine.open(directory, options);
        engine.onCheckpoint(checkpoints::incrementAndGet);
        String padding = "x".repeat(200);
        while (engine.stats().walBytes() < CheckpointThreshold.FLOOR + (1 << 20)) {
            int batch = commits++;
            engine.write(txn -> {
                for (int i = 0; i < 200; i++) {
                    txn.createNode(EngineTest.NODE, "node-" + batch + "-" + i + padding);
                }
                return null;
            });
        }
        long deadline = System.currentTimeMillis() + 10_000;
        while (checkpoints.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        CheckpointThreshold threshold = engine.checkpointThreshold();
        engine.halt();
        assertTrue(checkpoints.get() > 0, "no background checkpoint after " + commits + " commits");
        assertEquals(CheckpointThreshold.Source.AUTOMATIC, threshold.source());
        assertTrue(threshold.bytes() < CheckpointThreshold.CEILING, "threshold " + threshold);
        try (StorageEngine recovered = StorageEngine.open(directory, options)) {
            assertTrue(recovered.recovery().replayedCommits() < commits,
                    recovered.recovery().replayedCommits() + " of " + commits + " commits replayed");
            assertEquals(200L * commits, (long) recovered.read(snapshot -> snapshot.countOfType(0, EngineTest.NODE)));
        }
    }

    @Test
    void aStoredThresholdSurvivesCloseAndReopenAndAutoClearsIt() {
        EngineOptions configured = EngineTest.small().withCheckpointWalBytes(128L << 20);
        try (StorageEngine engine = StorageEngine.open(directory, configured)) {
            assertEquals(CheckpointThreshold.Source.CONFIGURATION, engine.checkpointThreshold().source());
            assertEquals(new CheckpointThreshold(64L << 20, CheckpointThreshold.Source.DATABASE), engine.checkpointAt(OptionalLong.of(64L << 20)));
            assertThrows(HStoreException.class, () -> engine.checkpointAt(OptionalLong.of(0)));
        }
        try (StorageEngine engine = StorageEngine.open(directory, EngineTest.small())) {
            assertEquals(new CheckpointThreshold(64L << 20, CheckpointThreshold.Source.DATABASE), engine.checkpointThreshold());
            assertEquals(CheckpointThreshold.Source.AUTOMATIC, engine.checkpointAt(OptionalLong.empty()).source());
        }
        try (StorageEngine engine = StorageEngine.open(directory, configured)) {
            assertEquals(new CheckpointThreshold(128L << 20, CheckpointThreshold.Source.CONFIGURATION), engine.checkpointThreshold());
        }
    }

    @ParameterizedTest
    @EnumSource(value = CrashPoint.class, names = {"WAL_SYNC", "CHECKPOINT_PAGES", "CHECKPOINT_CATALOG", "CHECKPOINT_JOURNAL"})
    void aStoredThresholdSurvivesACrash(CrashPoint point) {
        AtomicBoolean armed = new AtomicBoolean(false);
        EngineOptions options = EngineTest.small().withFaults(reached -> {
            if (reached == point && armed.compareAndSet(true, false)) {
                throw new CrashPoint.SimulatedCrash(point);
            }
        });
        StorageEngine engine = StorageEngine.open(directory, options);
        engine.write(txn -> txn.createNode(EngineTest.NODE, "before"));
        engine.checkpointAt(OptionalLong.of(64L << 20));
        armed.set(true);
        if (point == CrashPoint.WAL_SYNC) {
            assertThrows(CrashPoint.SimulatedCrash.class, () -> engine.write(txn -> txn.createNode(EngineTest.NODE, "doomed")));
        } else {
            engine.write(txn -> txn.createNode(EngineTest.NODE, "after"));
            assertThrows(CrashPoint.SimulatedCrash.class, engine::checkpoint);
        }
        engine.halt();
        try (StorageEngine recovered = StorageEngine.open(directory, EngineTest.small())) {
            assertEquals(new CheckpointThreshold(64L << 20, CheckpointThreshold.Source.DATABASE), recovered.checkpointThreshold());
        }
    }

    @Test
    void aStoredThresholdTakesEffectWithoutARestartAndWinsOverTheConfiguration() throws InterruptedException {
        EngineOptions options = EngineTest.small().withCheckpointWalBytes(Long.MAX_VALUE).withWriteBufferBytes(1L << 30)
                .withMaintenanceInterval(Duration.ofMillis(50));
        AtomicInteger checkpoints = new AtomicInteger();
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            engine.onCheckpoint(checkpoints::incrementAndGet);
            engine.checkpointAt(OptionalLong.of(1L << 20));
            String padding = "x".repeat(200);
            for (int batch = 0; engine.stats().walBytes() < 2L << 20; batch++) {
                int current = batch;
                engine.write(txn -> {
                    for (int i = 0; i < 200; i++) {
                        txn.createNode(EngineTest.NODE, "node-" + current + "-" + i + padding);
                    }
                    return null;
                });
            }
            long deadline = System.currentTimeMillis() + 10_000;
            while (checkpoints.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(checkpoints.get() > 0, "no background checkpoint after " + engine.stats().walBytes() + " bytes of log");
        }
    }

    @Test
    void aCommitThatPassesTheThresholdWakesTheMaintenanceThread() throws InterruptedException {
        EngineOptions options = EngineTest.small().withWriteBufferBytes(1L << 30).withMaintenanceInterval(Duration.ofHours(1));
        AtomicInteger checkpoints = new AtomicInteger();
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            engine.onCheckpoint(checkpoints::incrementAndGet);
            String padding = "x".repeat(200);
            for (int batch = 0; engine.stats().walBytes() <= CheckpointThreshold.FLOOR; batch++) {
                int current = batch;
                engine.write(txn -> {
                    for (int i = 0; i < 200; i++) {
                        txn.createNode(EngineTest.NODE, "node-" + current + "-" + i + padding);
                    }
                    return null;
                });
            }
            long deadline = System.currentTimeMillis() + 10_000;
            while (checkpoints.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, checkpoints.get(), "background checkpoints after " + engine.stats().walBytes() + " bytes of log");
        }
    }
}
