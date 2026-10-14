// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db;

import io.hstore.engine.EngineOptions;
import io.hstore.engine.StorageEngine;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadAllocationTest {

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    @TempDir
    Path directory;

    @Test
    void openingAndClosingASnapshotAllocatesOnlyTheSnapshot() {
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, DatabaseOptions.defaults()
                .withEngine(EngineOptions.defaults().withPageSize(4096)))) {
            database.write(writer -> writer.defineNode("Person", List.of()));
            for (int i = 0; i < 200; i++) {
                int index = i;
                database.write(writer -> writer.node("Person", "p" + index, Map.of()));
            }
            StorageEngine engine = database.engine();
            for (int i = 0; i < 100_000; i++) {
                engine.snapshot().close();
            }
            int rounds = 10_000;
            long before = THREADS.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < rounds; i++) {
                engine.snapshot().close();
            }
            long perSnapshot = (THREADS.getCurrentThreadAllocatedBytes() - before) / rounds;
            assertTrue(perSnapshot <= 48, perSnapshot + " bytes allocated per snapshot");
        }
    }
}
