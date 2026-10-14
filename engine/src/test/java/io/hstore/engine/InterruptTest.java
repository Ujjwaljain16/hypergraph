// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterruptTest {

    @TempDir
    Path directory;

    @Test
    void anInterruptedCallerLeavesTheEngineWorking() throws Exception {
        StorageEngine engine = StorageEngine.open(directory, EngineTest.small());
        engine.write(txn -> txn.createNode(EngineTest.NODE, "before"));
        onInterruptedThread(() -> {
            engine.write(txn -> txn.createNode(EngineTest.NODE, "interrupted"));
            engine.read(snapshot -> snapshot.resolve(0, EngineTest.NODE, "before"));
            engine.feed().replay(0).count();
            engine.checkpoint();
        });
        long events = engine.feed().replay(0).count();
        for (int i = 0; i < 200; i++) {
            String key = "after-" + i;
            engine.write(txn -> txn.createNode(EngineTest.NODE, key));
        }
        assertEquals(events + 200, engine.feed().replay(0).count());
        engine.verifyReferences();
        onInterruptedThread(engine::close);
        try (StorageEngine reopened = StorageEngine.open(directory, EngineTest.small())) {
            for (String key : List.of("before", "interrupted", "after-199")) {
                assertTrue(reopened.read(snapshot -> snapshot.resolve(0, EngineTest.NODE, key)).isPresent(), key);
            }
            reopened.verifyReferences();
        }
    }

    private static void onInterruptedThread(Runnable work) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean keptInterrupt = new AtomicBoolean();
        Thread thread = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt();
            try {
                work.run();
                keptInterrupt.set(Thread.currentThread().isInterrupted());
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        thread.join();
        assertNull(failure.get());
        assertTrue(keptInterrupt.get());
    }
}
