// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeCacheTest {

    private static final int PAGE = 16 * 1024;

    private static Node node(long key) {
        return Leaf.owned(TreeTest.LONGS, null, new long[]{key}, new Object[]{key}, 1);
    }

    @Test
    void residentBytesStayWithinTheBudget() {
        long budget = 1 << 20;
        NodeCache cache = new NodeCache(budget, PAGE);
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(3);
        for (long page = 0; page < 20_000; page++) {
            cache.put(page, node(page), 100 + random.nextInt(16 * 1024));
            assertTrue(cache.residentBytes() <= budget, "resident " + cache.residentBytes() + " after page " + page);
            if (page % 7 == 0) {
                cache.get(random.nextLong(page + 1));
            }
        }
        assertTrue(cache.residentBytes() > budget / 2, "cache should stay mostly full, holds " + cache.residentBytes());
    }

    @Test
    void pagesThatKeepBeingReadSurviveColdInserts() {
        NodeCache cache = new NodeCache(1 << 20, PAGE);
        long[] hot = LongStream.range(0, 32).toArray();
        for (long page : hot) {
            cache.put(page, node(page), 4096);
        }
        for (long cold = 1_000; cold < 50_000; cold++) {
            cache.put(cold, node(cold), 4096);
            for (long page : hot) {
                cache.get(page);
            }
        }
        for (long page : hot) {
            assertTrue(cache.get(page) != null, "hot page " + page + " was evicted");
        }
    }

    @Test
    void aNodeLargerThanItsShardIsNotCached() {
        NodeCache cache = new NodeCache(1 << 20, PAGE);
        cache.put(7, node(7), (1 << 20) + 1);
        assertNull(cache.get(7));
        assertEquals(0, cache.residentBytes());
    }

    @Test
    void puttingAPageAgainReplacesItAndItsSize() {
        NodeCache cache = new NodeCache(1 << 20, PAGE);
        Node first = node(1);
        Node second = node(2);
        cache.put(9, first, 1_000);
        cache.put(9, second, 5_000);
        assertSame(second, cache.get(9));
        assertEquals(5_000, cache.residentBytes());
        cache.clear();
        assertEquals(0, cache.residentBytes());
        assertNull(cache.get(9));
    }

    @Test
    void aNodeAsLargeAsAPageIsCachedEvenWhenTheBudgetIsSmall() {
        NodeCache cache = new NodeCache(2 << 20, 1 << 20);
        Node node = node(1);
        cache.put(1, node, 3 << 19);
        assertSame(node, cache.get(1));
    }

    @Test
    void largeBudgetsAreSplitIntoShards() {
        assertEquals(16, NodeCache.shardsFor(256L << 20, PAGE));
        assertEquals(1, NodeCache.shardsFor(1 << 20, PAGE));
        assertEquals(1, NodeCache.shardsFor(1, PAGE));
    }

    @Test
    void everyPageCachedLongEnoughIsFoundAndNothingElseIs() {
        NodeCache cache = new NodeCache(64L << 20, PAGE);
        for (long page = 1; page <= 50_000; page++) {
            cache.put(page, node(page), 100);
            if (page % 3 == 0) {
                cache.put(page, node(page), 100);
            }
        }
        for (long page = 1; page <= 50_000; page++) {
            assertEquals(page, ((Leaf) cache.get(page)).firstKey());
        }
        for (long page = 50_001; page <= 60_000; page++) {
            assertNull(cache.get(page));
        }
        cache.clear();
        assertNull(cache.get(1));
        assertEquals(0, cache.residentBytes());
    }

    @Test
    void readersSeeTheRightNodeWhileWritersEvictAndGrowTheTable() throws Exception {
        NodeCache cache = new NodeCache(256 * 1024, PAGE);
        AtomicBoolean done = new AtomicBoolean();
        AtomicLong wrong = new AtomicLong();
        AtomicLong found = new AtomicLong();
        try (ExecutorService readers = Executors.newFixedThreadPool(4)) {
            List<Future<?>> running = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(t);
                running.add(readers.submit(() -> {
                    while (!done.get()) {
                        long page = 1 + random.nextLong(20_000);
                        Node node = cache.get(page);
                        if (node != null) {
                            found.incrementAndGet();
                            if (((Leaf) node).firstKey() != page) {
                                wrong.incrementAndGet();
                            }
                        }
                    }
                }));
            }
            RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(99);
            for (int round = 0; round < 300_000; round++) {
                long page = 1 + random.nextLong(20_000);
                cache.put(page, node(page), 64 + random.nextInt(512));
            }
            done.set(true);
            for (Future<?> reader : running) {
                reader.get();
            }
        }
        assertEquals(0, wrong.get());
        assertTrue(found.get() > 0);
        assertTrue(cache.residentBytes() <= 256 * 1024);
    }
}
