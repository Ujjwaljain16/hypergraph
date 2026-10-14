// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.page.PowerLoss;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.txn.Durability;
import io.hstore.engine.txn.WalMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerLossTest {

    @TempDir
    Path directory;

    @Test
    void aDatabaseCreatedJustBeforeAPowerLossStillOpens() {
        EngineOptions options = options(Durability.SYNC, WalMode.PAGE_REFERENCES);
        try (PowerLoss loss = PowerLoss.watch(directory)) {
            StorageEngine engine = StorageEngine.open(directory, options);
            engine.write(txn -> txn.createNode(EngineTest.NODE, "acknowledged"));
            loss.powerOff();
            engine.halt();
            loss.restore(PowerLoss.Tear.NOTHING, new Random(1));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            assertTrue(engine.read(snapshot -> snapshot.resolve(0, EngineTest.NODE, "acknowledged")).isPresent());
        }
    }

    @ParameterizedTest
    @EnumSource(WalMode.class)
    void pagesInASegmentCreatedSinceTheLastCheckpointSurvive(WalMode mode) {
        EngineOptions options = options(Durability.SYNC, mode);
        long edge;
        try (PowerLoss loss = PowerLoss.watch(directory)) {
            StorageEngine engine = StorageEngine.open(directory, options);
            edge = engine.write(txn -> {
                long created = txn.createEdge(EngineTest.EDGE, EdgeKind.SET);
                txn.load(created, LongStream.range(0, 6_000).mapToObj(Incidence::of).toList());
                return created;
            });
            loss.powerOff();
            engine.halt();
            loss.restore(PowerLoss.Tear.NOTHING, new Random(1));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            assertEquals(0, engine.recovery().discardedCommits());
            assertEquals(6_000, (long) engine.read(snapshot -> snapshot.requireEdge(edge).size()));
            engine.verifyReferences();
        }
    }

    @Test
    void aPageDirectoryWhoseFirstPageNumberWasNeverForcedStillHandsOutNumbers() {
        EngineOptions options = options(Durability.SYNC, WalMode.PAGE_REFERENCES);
        try (PowerLoss loss = PowerLoss.watch(directory)) {
            loss.crashOn(event -> event.kind() == PowerLoss.Kind.MAPPED_FORCE && event.file().endsWith("pages.ref"));
            assertThrows(PowerLoss.Cut.class, () -> StorageEngine.open(directory, options));
            loss.restore(PowerLoss.Tear.NOTHING, new Random(1));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            engine.write(txn -> txn.createNode(EngineTest.NODE, "first"));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            assertTrue(engine.read(snapshot -> snapshot.resolve(0, EngineTest.NODE, "first")).isPresent());
            engine.verifyReferences();
        }
    }

    private static EngineOptions options(Durability durability, WalMode mode) {
        return EngineTest.small().withDurability(durability).withWalMode(mode).withWalSegmentBytes(48 << 10)
                .withCheckpointWalBytes(Long.MAX_VALUE).withMaintenanceInterval(Duration.ofHours(1));
    }
}
