// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.txn.CrashPoint;
import io.hstore.engine.txn.Transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TxnIdReuseTest {

    @TempDir
    Path directory;

    @Test
    void anUnacknowledgedTransactionStaysGoneAfterASecondCrash() {
        crashDuringCommitThenCrashAgain(doomed -> doomed.createNode(EngineTest.NODE, "ghost"));

        try (StorageEngine third = StorageEngine.open(directory, EngineTest.small())) {
            third.read(snapshot -> {
                assertTrue(snapshot.resolve(0, EngineTest.NODE, "real").isPresent());
                assertTrue(snapshot.resolve(0, EngineTest.NODE, "ghost").isEmpty());
                assertEquals(3, snapshot.countOfType(0, EngineTest.NODE));
                return null;
            });
        }
    }

    @Test
    void anUnacknowledgedDeleteDoesNotRemoveCommittedDataAfterASecondCrash() {
        crashDuringCommitThenCrashAgain(doomed -> doomed.delete(1));

        try (StorageEngine third = StorageEngine.open(directory, EngineTest.small())) {
            third.read(snapshot -> {
                assertTrue(snapshot.atom(1).isPresent());
                assertTrue(snapshot.resolve(0, EngineTest.NODE, "real").isPresent());
                return null;
            });
        }
    }

    private void crashDuringCommitThenCrashAgain(Consumer<Transaction> doomedWork) {
        AtomicBoolean armed = new AtomicBoolean(false);
        EngineOptions crashing = EngineTest.small().withFaults(reached -> {
            if (reached == CrashPoint.WAL_APPEND && armed.compareAndSet(true, false)) {
                throw new CrashPoint.SimulatedCrash(reached);
            }
        });
        try (StorageEngine seeded = StorageEngine.open(directory, EngineTest.small())) {
            assertEquals(1L, (long) seeded.write(txn -> txn.createNode(EngineTest.NODE, "base")));
            seeded.write(txn -> txn.createNode(EngineTest.NODE, "other"));
        }
        StorageEngine first = StorageEngine.open(directory, crashing);
        armed.set(true);
        Transaction doomed = first.begin();
        doomedWork.accept(doomed);
        assertThrows(CrashPoint.SimulatedCrash.class, doomed::commit);
        first.halt();

        StorageEngine second = StorageEngine.open(directory, EngineTest.small());
        second.write(txn -> txn.createNode(EngineTest.NODE, "real"));
        second.halt();
    }
}
