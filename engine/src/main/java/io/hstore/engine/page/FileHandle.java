// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
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

    private final Path path;
    private final ReentrantLock reopening = new ReentrantLock();
    private volatile FileChannel channel;
    private volatile boolean closed;

    private FileHandle(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
    }

    public static FileHandle open(Path path) throws IOException {
        return new FileHandle(path, FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE));
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

    private void reopen(FileChannel failed) throws IOException {
        reopening.lock();
        try {
            if (!closed && channel == failed) {
                channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
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
