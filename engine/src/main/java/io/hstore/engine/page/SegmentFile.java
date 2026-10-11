// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import io.hstore.engine.HStoreException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

final class SegmentFile implements AutoCloseable {

    private static final int FIRST_READ = 4096;
    private static final long REMAP_BYTES = 4L << 20;

    private final int id;
    private final Path path;
    private final FileHandle file;
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final ReentrantLock remapping = new ReentrantLock();
    private volatile MemorySegment mapped = MemorySegment.NULL;
    private Arena arena;
    private volatile boolean sealed;

    private SegmentFile(int id, Path path, FileHandle file) {
        this.id = id;
        this.path = path;
        this.file = file;
    }

    static SegmentFile open(int id, Path path) {
        try {
            return new SegmentFile(id, path, FileHandle.open(path));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open segment " + path, e);
        }
    }

    static Path pathFor(Path directory, int id) {
        return directory.resolve("%08x.seg".formatted(id));
    }

    static int idOf(Path file) {
        String name = file.getFileName().toString();
        return Integer.parseInt(name.substring(0, name.indexOf('.')), 16);
    }

    int id() {
        return id;
    }

    long unitsOnDisk() {
        try {
            return PageId.unitsFor(file.size());
        } catch (IOException e) {
            throw HStoreException.io("cannot size segment " + path, e);
        }
    }

    void seal() {
        sealed = true;
    }

    MemorySegment read(long position, int maximum) {
        while (true) {
            MemorySegment view = covering(position + PageHeader.SIZE);
            if (view == null) {
                return copy(position, maximum);
            }
            try {
                return read(view, position, maximum);
            } catch (IllegalStateException closed) {
                if (view.scope().isAlive()) {
                    throw closed;
                }
            }
        }
    }

    private MemorySegment read(MemorySegment view, long position, int maximum) {
        long length = PageHeader.SIZE + Integer.toUnsignedLong(PageHeader.payloadLengthAt(view, position));
        if (length > maximum) {
            return onHeap(view, position, Math.min(maximum, view.byteSize() - position));
        }
        if (position + length > view.byteSize()) {
            view = covering(position + length);
            if (view == null) {
                return copy(position, maximum);
            }
        }
        return onHeap(view, position, length);
    }

    MemorySegment view() {
        try {
            MemorySegment whole = covering(file.size());
            return whole == null ? MemorySegment.NULL : whole;
        } catch (IOException e) {
            throw HStoreException.io("cannot size segment " + path, e);
        }
    }

    long mappedBytes() {
        return mapped.byteSize();
    }

    private static MemorySegment onHeap(MemorySegment view, long position, long length) {
        byte[] image = new byte[Math.toIntExact(length)];
        MemorySegment.copy(view, ValueLayout.JAVA_BYTE, position, image, 0, image.length);
        return MemorySegment.ofArray(image).asReadOnly();
    }

    private MemorySegment covering(long required) {
        MemorySegment current = mapped;
        if (required <= current.byteSize()) {
            return current;
        }
        if (!sealed && required - current.byteSize() < REMAP_BYTES) {
            return null;
        }
        remapping.lock();
        try {
            current = mapped;
            if (required <= current.byteSize()) {
                return current;
            }
            long size = file.size();
            if (size < required) {
                return null;
            }
            Arena replacement = Arena.ofShared();
            try {
                current = file.call(channel -> channel.map(FileChannel.MapMode.READ_ONLY, 0, size, replacement));
            } catch (IOException | RuntimeException e) {
                replacement.close();
                throw e;
            }
            mapped = current;
            unmap();
            arena = replacement;
            return current;
        } catch (IOException e) {
            throw HStoreException.io("cannot map segment " + id, e);
        } finally {
            remapping.unlock();
        }
    }

    private MemorySegment copy(long position, int maximum) {
        ByteBuffer first = ByteBuffer.allocate(Math.min(maximum, FIRST_READ));
        fill(first, position);
        if (first.position() < PageHeader.SIZE) {
            return MemorySegment.ofBuffer(first.flip()).asReadOnly();
        }
        int length = PageHeader.SIZE + PageHeader.payloadLength(MemorySegment.ofArray(first.array()));
        if (length < PageHeader.SIZE || length > maximum) {
            return MemorySegment.ofBuffer(first.flip()).asReadOnly();
        }
        if (length <= first.position()) {
            return MemorySegment.ofBuffer(first.flip()).asSlice(0, length).asReadOnly();
        }
        ByteBuffer whole = ByteBuffer.allocate(length).put(first.flip());
        fill(whole, position);
        return MemorySegment.ofBuffer(whole.flip()).asReadOnly();
    }

    private void fill(ByteBuffer buffer, long position) {
        try {
            file.run(channel -> {
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer, position + buffer.position()) < 0) {
                        return;
                    }
                }
            });
        } catch (IOException e) {
            throw HStoreException.io("read failed in segment " + id + " at byte " + position, e);
        }
    }

    void write(long position, MemorySegment image) {
        ByteBuffer buffer = image.asByteBuffer();
        try {
            file.run(channel -> {
                while (buffer.hasRemaining()) {
                    channel.write(buffer, position + buffer.position());
                }
            });
            dirty.set(true);
        } catch (IOException e) {
            throw HStoreException.io("write failed in segment " + id + " at byte " + position, e);
        }
    }

    void force() {
        if (dirty.getAndSet(false)) {
            try {
                file.run(channel -> channel.force(false));
            } catch (IOException e) {
                dirty.set(true);
                throw HStoreException.io("sync failed for segment " + id, e);
            }
        }
    }

    void delete() {
        close();
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void unmap() {
        if (arena != null) {
            arena.close();
            arena = null;
        }
    }

    @Override
    public void close() {
        remapping.lock();
        try {
            mapped = MemorySegment.NULL;
            unmap();
        } finally {
            remapping.unlock();
        }
        try {
            file.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
