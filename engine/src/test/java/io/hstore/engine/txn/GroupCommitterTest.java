// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.txn;

import io.hstore.engine.HStoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupCommitterTest {

    private static final class Appender {
        private final ReentrantLock commitLock = new ReentrantLock();
        private final GroupCommitter committer;
        private final List<Integer> published = new CopyOnWriteArrayList<>();
        private int next;

        Appender(GroupCommitter committer) {
            this.committer = committer;
        }

        GroupCommitter.Pending append(Runnable also) {
            commitLock.lock();
            try {
                int sequence = next++;
                GroupCommitter.Pending pending = new GroupCommitter.Pending(null, () -> {
                    published.add(sequence);
                    also.run();
                });
                committer.submit(pending);
                return pending;
            } finally {
                commitLock.unlock();
            }
        }
    }

    @Test
    void aLoneCommitIsPublishedOnItsOwnThread() {
        GroupCommitter committer = new GroupCommitter(_ -> {
        });
        Appender appender = new Appender(committer);
        List<Thread> publishers = new ArrayList<>();
        GroupCommitter.Pending pending = appender.append(() -> publishers.add(Thread.currentThread()));
        committer.await(pending);
        assertEquals(List.of(Thread.currentThread()), publishers);
        committer.close();
    }

    @Test
    void concurrentCommitsArePublishedInOrderAndShareBarriers() throws Exception {
        AtomicInteger barriers = new AtomicInteger();
        GroupCommitter committer = new GroupCommitter(_ -> {
            barriers.incrementAndGet();
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Appender appender = new Appender(committer);
        try (ExecutorService threads = Executors.newFixedThreadPool(8)) {
            List<? extends Future<?>> futures = IntStream.range(0, 8).mapToObj(_ -> threads.submit(() -> {
                for (int i = 0; i < 200; i++) {
                    committer.await(appender.append(() -> {
                    }));
                }
            })).toList();
            for (Future<?> future : futures) {
                future.get();
            }
        }
        assertEquals(IntStream.range(0, 1_600).boxed().toList(), appender.published);
        assertTrue(barriers.get() < 1_600, "every commit paid its own barrier: " + barriers.get());
        assertTrue(committer.averageBatch() > 1, "average batch " + committer.averageBatch());
        committer.close();
    }

    @Test
    void aFailedBarrierFailsEveryCommitAfterIt() {
        AtomicInteger calls = new AtomicInteger();
        GroupCommitter committer = new GroupCommitter(_ -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("disk full");
            }
        });
        Appender appender = new Appender(committer);
        committer.await(appender.append(() -> {
        }));
        GroupCommitter.Pending failing = appender.append(() -> {
        });
        assertTrue(assertThrows(HStoreException.class, () -> committer.await(failing)).getMessage().contains("durability failure"));
        assertThrows(HStoreException.class, () -> appender.append(() -> {
        }));
        assertEquals(List.of(0), appender.published);
        committer.close();
    }

    @Test
    void anInterruptedCommitterLeadsABatchWithoutClosingTheLog(@TempDir Path dir) throws IOException {
        try (FileChannel log = FileChannel.open(dir.resolve("log"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            Runnable write = () -> {
                try {
                    log.write(ByteBuffer.allocate(8));
                    log.force(false);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
            GroupCommitter committer = new GroupCommitter(_ -> write.run());
            Appender appender = new Appender(committer);
            appender.append(() -> Thread.currentThread().interrupt());
            GroupCommitter.Pending second = appender.append(write);
            Thread.currentThread().interrupt();
            try {
                committer.await(second);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            committer.await(appender.append(() -> {
            }));
            assertTrue(log.isOpen());
            assertEquals(List.of(0, 1, 2), appender.published);
            committer.close();
        }
    }
}
