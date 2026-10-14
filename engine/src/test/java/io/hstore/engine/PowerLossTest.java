// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.page.PowerLoss;
import io.hstore.engine.txn.Durability;
import io.hstore.engine.txn.WalMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;

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

    private static EngineOptions options(Durability durability, WalMode mode) {
        return EngineTest.small().withDurability(durability).withWalMode(mode).withWalSegmentBytes(48 << 10)
                .withCheckpointWalBytes(Long.MAX_VALUE).withMaintenanceInterval(Duration.ofHours(1));
    }
}
