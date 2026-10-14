// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilePoolTest {

    @TempDir
    Path directory;

    @Test
    void filesOpenOnDemandAndTheLeastRecentlyUsedIsClosedFirst() throws Exception {
        FilePool pool = new FilePool(2);
        FilePool.Entry a = pool.entry(directory.resolve("a"));
        FilePool.Entry b = pool.entry(directory.resolve("b"));
        FilePool.Entry c = pool.entry(directory.resolve("c"));
        assertEquals(0, pool.open());
        List<FileChannel> seen = new ArrayList<>();
        a.run(seen::add);
        b.run(seen::add);
        a.run(channel -> assertSame(seen.getFirst(), channel));
        assertEquals(2, pool.open());
        c.run(seen::add);
        assertEquals(2, pool.open());
        assertTrue(seen.get(0).isOpen(), "a was used more recently than b");
        assertFalse(seen.get(1).isOpen(), "b was the least recently used");
        b.run(seen::add);
        assertFalse(seen.get(0).isOpen(), "a is now the least recently used");
        assertTrue(seen.get(2).isOpen());
        assertEquals(2, pool.open());
    }

    @Test
    void aFileIsForcedBeforeItIsClosedToMakeRoom() throws Exception {
        FilePool pool = new FilePool(1);
        FilePool.Entry a = pool.entry(directory.resolve("a"));
        FilePool.Entry b = pool.entry(directory.resolve("b"));
        a.write(channel -> channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3}), 0));
        assertEquals(1, pool.unsynced());
        assertEquals(0, pool.forces());
        b.run(channel -> channel.read(ByteBuffer.allocate(1), 0));
        assertEquals(1, pool.forces());
        assertEquals(0, pool.unsynced());
        pool.sync();
        assertEquals(1, pool.forces());
        assertEquals(3, Files.size(directory.resolve("a")));
    }

    @Test
    void syncForcesOnlyTheFilesWrittenSinceTheLastSync() throws Exception {
        FilePool pool = new FilePool(64);
        List<FilePool.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            FilePool.Entry entry = pool.entry(directory.resolve("f" + i));
            entry.write(channel -> channel.write(ByteBuffer.wrap(new byte[]{7}), 0));
            entries.add(entry);
        }
        pool.sync();
        assertEquals(20, pool.forces());
        pool.sync();
        assertEquals(20, pool.forces());
        entries.get(3).write(channel -> channel.write(ByteBuffer.wrap(new byte[]{8}), 1));
        entries.get(3).run(channel -> channel.read(ByteBuffer.allocate(2), 0));
        entries.get(9).run(channel -> channel.read(ByteBuffer.allocate(1), 0));
        pool.sync();
        assertEquals(21, pool.forces());
    }

    @Test
    void aFileInUseIsNeverClosedAndTheBoundHoldsUnderContention() throws Exception {
        int capacity = 3;
        FilePool pool = new FilePool(capacity);
        List<FilePool.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            FilePool.Entry entry = pool.entry(directory.resolve("f" + i));
            int value = i;
            entry.write(channel -> channel.write(ByteBuffer.allocate(4096).putInt(0, value), 0));
            entries.add(entry);
        }
        AtomicInteger closedUnderneath = new AtomicInteger();
        AtomicInteger overBound = new AtomicInteger();
        try (ExecutorService threads = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                Random random = new Random(t);
                results.add(threads.submit(() -> {
                    int calls = 0;
                    for (int i = 0; i < 2_000; i++) {
                        int pick = random.nextInt(entries.size());
                        FilePool.Entry entry = entries.get(pick);
                        int read = entry.call(channel -> {
                            ByteBuffer buffer = ByteBuffer.allocate(4);
                            channel.read(buffer, 0);
                            Thread.yield();
                            if (!channel.isOpen()) {
                                closedUnderneath.incrementAndGet();
                            }
                            if (pool.open() > capacity) {
                                overBound.incrementAndGet();
                            }
                            if (random.nextInt(4) == 0) {
                                channel.write(ByteBuffer.allocate(4).putInt(0, pick), 0);
                            }
                            return buffer.getInt(0);
                        });
                        assertEquals(pick, read);
                        calls++;
                    }
                    return calls;
                }));
            }
            for (Future<Integer> result : results) {
                assertEquals(2_000, result.get());
            }
        }
        assertEquals(0, closedUnderneath.get());
        assertEquals(0, overBound.get());
        assertTrue(pool.open() <= capacity);
    }

    @Test
    void aClosedEntryIsNotReopenedOrRecreated() throws Exception {
        FilePool pool = new FilePool(4);
        Path path = directory.resolve("gone");
        FilePool.Entry entry = pool.entry(path);
        entry.write(channel -> channel.write(ByteBuffer.wrap(new byte[]{1}), 0));
        entry.close();
        Files.delete(path);
        assertEquals(0, pool.open());
        assertThrows(ClosedChannelException.class, () -> entry.run(channel -> channel.read(ByteBuffer.allocate(1), 0)));
        pool.sync();
        assertFalse(Files.exists(path));
    }
}
