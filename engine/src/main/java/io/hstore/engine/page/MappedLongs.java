// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import io.hstore.engine.HStoreException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Arrays;

final class MappedLongs implements AutoCloseable {

    static final long HEADER = 64;

    private static final int CHUNK_SHIFT = 23;
    private static final long CHUNK_BYTES = 1L << CHUNK_SHIFT;
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle ENTRY = LONG.varHandle();

    private final Path path;
    private final FileHandle file;
    private boolean fresh;
    private volatile MemorySegment[] chunks = new MemorySegment[0];

    private MappedLongs(Path path, FileHandle file) {
        this.path = path;
        this.file = file;
    }

    static MappedLongs open(Path path, int magic, int format, String what) {
        try {
            FileHandle file = FileHandle.open(path);
            long size = file.size();
            MappedLongs longs = new MappedLongs(path, file);
            longs.mapThrough(size == 0 ? 0 : (int) ((size - 1) >>> CHUNK_SHIFT));
            MemorySegment header = longs.chunks[0];
            longs.fresh = header.get(INT, 0) == 0 && header.get(INT, 4) == 0 && header.get(LONG, 8) == 0;
            if (longs.fresh) {
                header.set(INT, 0, magic);
                header.set(INT, 4, format);
                file.force(header);
                FileHandle.syncDirectory(path.getParent());
            } else if (header.get(INT, 0) != magic || header.get(INT, 4) != format) {
                file.close();
                throw HStoreException.invalid(what + " " + path + " has an unknown format");
            }
            return longs;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open " + what + " " + path, e);
        }
    }

    boolean fresh() {
        return fresh;
    }

    long header(int offset) {
        return chunks[0].get(LONG, offset);
    }

    void header(int offset, long value) {
        chunks[0].set(LONG, offset, value);
    }

    long get(long index) {
        long position = HEADER + index * Long.BYTES;
        MemorySegment[] mapped = chunks;
        int chunk = (int) (position >>> CHUNK_SHIFT);
        return chunk < mapped.length ? (long) ENTRY.getAcquire(mapped[chunk], position & (CHUNK_BYTES - 1)) : 0;
    }

    void put(long index, long value) {
        long position = HEADER + index * Long.BYTES;
        int chunk = (int) (position >>> CHUNK_SHIFT);
        if (chunk >= chunks.length) {
            mapThrough(chunk);
        }
        ENTRY.setRelease(chunks[chunk], position & (CHUNK_BYTES - 1), value);
    }

    void force() {
        file.force(chunks);
    }

    private synchronized void mapThrough(int last) {
        MemorySegment[] mapped = chunks;
        if (last < mapped.length) {
            return;
        }
        MemorySegment[] grown = Arrays.copyOf(mapped, last + 1);
        try {
            for (int chunk = mapped.length; chunk <= last; chunk++) {
                long offset = chunk * CHUNK_BYTES;
                grown[chunk] = file.call(channel -> channel.map(FileChannel.MapMode.READ_WRITE, offset, CHUNK_BYTES, Arena.ofAuto()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot grow " + path, e);
        }
        chunks = grown;
    }

    @Override
    public void close() {
        try {
            file.close();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot close " + path, e);
        }
    }
}
