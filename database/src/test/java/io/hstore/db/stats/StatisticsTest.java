// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.stats;

import io.hstore.engine.EngineOptions;
import io.hstore.engine.StorageEngine;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.txn.Durability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticsTest {

    static final int EDGE = 2;
    static final int MEMBERS = 8;

    @TempDir
    Path directory;

    static EngineOptions options() {
        return EngineOptions.defaults().withPageSize(4096).withHistoryLimit(64).withDurability(Durability.ASYNC);
    }

    @Test
    void aSmallStoreGetsStatisticsFromItsFirstChanges() throws InterruptedException {
        try (StorageEngine engine = StorageEngine.open(directory, options());
             Statistics statistics = new Statistics(engine)) {
            long[] next = {1L << 40};
            long changes = load(engine, next, 5, 100);
            caughtUp(engine, statistics);
            assertTrue(changes < 10_000, "changes " + changes);
            assertEquals(0, statistics.refreshes());
            changes += load(engine, next, 10, 100);
            caughtUp(engine, statistics);
            assertTrue(changes > 10_000, "changes " + changes);
            assertEquals(1, statistics.refreshes());
            assertEquals(1_200, statistics.catalog().edges());
            assertEquals(1, statistics.refreshes());
        }
    }

    @Test
    void aLargeStoreWaitsForATenthOfItsSizeToChange() throws InterruptedException {
        try (StorageEngine engine = StorageEngine.open(directory, options());
             Statistics statistics = new Statistics(engine)) {
            long[] next = {1L << 40};
            load(engine, next, 50, 1_000);
            caughtUp(engine, statistics);
            Statistics.Catalog catalog = statistics.refresh();
            long refreshes = statistics.refreshes();
            long size = catalog.atoms() + catalog.cardinality().sum();
            assertEquals(50_000L * (MEMBERS + 1), size);
            long changes = 0;
            while (changes + 1_000L * (MEMBERS + 1) <= size / 10) {
                changes += load(engine, next, 1, 1_000);
            }
            caughtUp(engine, statistics);
            assertTrue(changes > 30_000, "changes " + changes);
            assertEquals(refreshes, statistics.refreshes());
            load(engine, next, 2, 1_000);
            caughtUp(engine, statistics);
            assertEquals(refreshes + 1, statistics.refreshes());
        }
    }

    @Test
    void aBulkLoadRefreshesLogarithmicallyOften() throws InterruptedException {
        try (StorageEngine engine = StorageEngine.open(directory, options());
             Statistics statistics = new Statistics(engine)) {
            long[] next = {1L << 40};
            long changes = load(engine, next, 150, 1_000);
            caughtUp(engine, statistics);
            long everyTenThousand = changes / 10_000;
            long everyTenth = 10 + Math.round(Math.ceil(Math.log(changes / 100_000.0) / Math.log(1.1)));
            String message = statistics.refreshes() + " refreshes for " + changes + " changes";
            assertTrue(statistics.refreshes() <= everyTenth, message);
            assertTrue(statistics.refreshes() < everyTenThousand / 4, message);
        }
    }

    private static long load(StorageEngine engine, long[] next, int transactions, int edges) {
        for (int t = 0; t < transactions; t++) {
            engine.write(txn -> {
                for (int e = 0; e < edges; e++) {
                    long edge = txn.createEdge(EDGE, EdgeKind.SET);
                    long first = next[0];
                    next[0] += MEMBERS;
                    txn.load(edge, LongStream.range(first, first + MEMBERS).mapToObj(Incidence::of).toList());
                }
                return null;
            });
        }
        return (long) transactions * edges * (MEMBERS + 1);
    }

    private static void caughtUp(StorageEngine engine, Statistics statistics) throws InterruptedException {
        long generation = engine.transactions().current().id();
        long deadline = System.currentTimeMillis() + 10_000;
        while (statistics.acknowledged() < generation && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(generation, statistics.acknowledged());
    }
}
