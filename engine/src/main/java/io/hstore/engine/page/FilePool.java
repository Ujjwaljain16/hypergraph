// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import io.hstore.engine.HStoreException;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

final class FilePool {

    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition released = lock.newCondition();
    private final LinkedHashSet<Entry> idle = new LinkedHashSet<>();
    private final Set<Entry> unsynced = ConcurrentHashMap.newKeySet();
    private final AtomicLong forces = new AtomicLong();
    private int open;

    FilePool(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("the file pool must allow at least one open file: " + capacity);
        }
        this.capacity = capacity;
    }

    Entry entry(Path path) {
        return new Entry(path);
    }

    int open() {
        lock.lock();
        try {
            return open;
        } finally {
            lock.unlock();
        }
    }

    int capacity() {
        return capacity;
    }

    long forces() {
        return forces.get();
    }

    int unsynced() {
        return unsynced.size();
    }

    void sync() {
        for (Entry entry : List.copyOf(unsynced)) {
            try {
                entry.force();
            } catch (IOException e) {
                throw HStoreException.io("sync failed for " + entry.path(), e);
            }
        }
    }

    private FileHandle acquire(Entry entry) throws IOException {
        lock.lock();
        try {
            while (true) {
                if (entry.closed) {
                    throw new ClosedChannelException();
                }
                if (entry.handle != null) {
                    if (entry.users++ == 0) {
                        idle.remove(entry);
                    }
                    return entry.handle;
                }
                if (open < capacity) {
                    entry.handle = openCreating(entry.path);
                    entry.users = 1;
                    open++;
                    return entry.handle;
                }
                Iterator<Entry> oldest = idle.iterator();
                if (!oldest.hasNext()) {
                    released.awaitUninterruptibly();
                    continue;
                }
                Entry victim = oldest.next();
                oldest.remove();
                FileHandle handle = victim.handle;
                victim.handle = null;
                lock.unlock();
                try {
                    victim.retire(handle);
                } finally {
                    lock.lock();
                    open--;
                    released.signalAll();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private static FileHandle openCreating(Path path) throws IOException {
        boolean created = !Files.exists(path);
        FileHandle handle = FileHandle.open(path);
        if (created) {
            FileHandle.syncDirectory(path.getParent());
        }
        return handle;
    }

        private void release(Entry entry) {
        lock.lock();
        try {
            if (--entry.users == 0) {
                if (!entry.closed) {
                    idle.add(entry);
                }
                released.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    private void close(Entry entry) throws IOException {
        FileHandle handle;
        lock.lock();
        try {
            entry.closed = true;
            unsynced.remove(entry);
            while (entry.users > 0) {
                released.awaitUninterruptibly();
            }
            idle.remove(entry);
            handle = entry.handle;
            entry.handle = null;
        } finally {
            lock.unlock();
        }
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } finally {
            lock.lock();
            try {
                open--;
                released.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    final class Entry {

        private final Path path;
        private final AtomicLong written = new AtomicLong();
        private final AtomicLong durable = new AtomicLong();
        private FileHandle handle;
        private int users;
        private volatile boolean closed;

        private Entry(Path path) {
            this.path = path;
        }

        Path path() {
            return path;
        }

        <T> T call(FileHandle.Io<T> io) throws IOException {
            FileHandle leased = acquire(this);
            try {
                return leased.call(io);
            } finally {
                release(this);
            }
        }

        void run(FileHandle.Action action) throws IOException {
            call(channel -> {
                action.run(channel);
                return null;
            });
        }

        void write(FileHandle.Action action) throws IOException {
            call(channel -> {
                action.run(channel);
                written.incrementAndGet();
                unsynced.add(this);
                return null;
            });
        }

        void force() throws IOException {
            unsynced.remove(this);
            long target = written.get();
            if (durable.get() >= target) {
                return;
            }
            try {
                run(channel -> force(channel, target));
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                unsynced.add(this);
                throw e;
            }
        }

        void close() throws IOException {
            FilePool.this.close(this);
        }

        private void force(FileChannel channel, long target) throws IOException {
            if (durable.get() >= target) {
                return;
            }
            channel.force(false);
            forces.incrementAndGet();
            durable.accumulateAndGet(target, Math::max);
        }

        private void retire(FileHandle handle) throws IOException {
            long target = written.get();
            try {
                handle.run(channel -> force(channel, target));
            } finally {
                handle.close();
            }
            unsynced.remove(this);
            if (durable.get() < written.get()) {
                unsynced.add(this);
            }
        }
    }
}
