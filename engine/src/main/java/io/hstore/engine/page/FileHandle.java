// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public final class FileHandle implements AutoCloseable {

    private static final int MAX_REOPENS = 64;

    @FunctionalInterface
    public interface Io<T> {
        T apply(FileChannel channel) throws IOException;
    }

    @FunctionalInterface
    public interface Action {
        void run(FileChannel channel) throws IOException;
    }

    @FunctionalInterface
    interface Channels {
        FileChannel open(Path path, OpenOption... options) throws IOException;

        default void forced(Path path) {
        }
    }

    static volatile Channels channels = FileChannel::open;

    private final Path path;
    private final ReentrantLock reopening = new ReentrantLock();
    private volatile FileChannel channel;
    private volatile boolean closed;

    private FileHandle(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
    }

    public static FileHandle open(Path path) throws IOException {
        return new FileHandle(path, channel(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE));
    }

    public static FileChannel channel(Path path, OpenOption... options) throws IOException {
        return channels.open(path, options);
    }

    public static void syncDirectory(Path directory) {
        try (FileChannel listing = channel(directory, StandardOpenOption.READ)) {
            listing.force(true);
        } catch (IOException _) {
        }
    }

    public static <T> T uninterrupted(Supplier<T> work) {
        boolean interrupted = Thread.interrupted();
        try {
            return work.get();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public <T> T call(Io<T> io) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            for (int attempt = 0; ; attempt++) {
                FileChannel current = channel;
                try {
                    return io.apply(current);
                } catch (ClosedChannelException e) {
                    if (e instanceof ClosedByInterruptException) {
                        interrupted |= Thread.interrupted();
                    }
                    if (closed || attempt >= MAX_REOPENS) {
                        throw e;
                    }
                    reopen(current);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void run(Action action) throws IOException {
        call(channel -> {
            action.run(channel);
            return null;
        });
    }

    public boolean isOpen() {
        return !closed;
    }

    public long size() throws IOException {
        return call(FileChannel::size);
    }

    void force(MemorySegment... mappings) {
        for (MemorySegment mapping : mappings) {
            mapping.force();
        }
        channels.forced(path);
    }

    private void reopen(FileChannel failed) throws IOException {
        reopening.lock();
        try {
            if (!closed && channel == failed) {
                channel = channel(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            }
        } finally {
            reopening.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        reopening.lock();
        try {
            closed = true;
            channel.close();
        } finally {
            reopening.unlock();
        }
    }
}
