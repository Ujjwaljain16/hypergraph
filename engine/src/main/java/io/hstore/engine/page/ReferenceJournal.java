// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import io.hstore.engine.HStoreException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.zip.CRC32C;

final class ReferenceJournal {

    static final String FILE = "pages.ref.journal";

    record Contents(long[] counts, long[] cleared) {
    }

    private static final int MAGIC = 0x4C4E524A;
    private static final int FORMAT = 1;
    private static final int HEADER = 24;
    private static final int BUFFER = 1 << 20;

    private ReferenceJournal() {
    }

    static void write(Path path, long lsn, ReferenceCounts counts, long[] cleared, int clearedCount) {
        boolean created = !Files.exists(path);
        try (FileChannel channel = FileHandle.channel(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            Writer out = new Writer(channel);
            out.putInt(MAGIC).putInt(FORMAT).putLong(lsn).putInt(counts.changed()).putInt(clearedCount);
            counts.forEachChange((index, entry) -> out.putLong(index).putLong(entry));
            for (int i = 0; i < clearedCount; i++) {
                out.putLong(cleared[i]);
            }
            out.finish();
            channel.force(true);
        } catch (IOException e) {
            throw HStoreException.io("cannot write reference journal " + path, e);
        }
        if (created) {
            FileHandle.syncDirectory(path.getParent());
        }
    }

    static Optional<Contents> read(Path path, long expectedLsn) {
        try {
            if (!Files.exists(path) || Files.size(path) < HEADER + Integer.BYTES) {
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(path);
            ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            CRC32C crc = new CRC32C();
            crc.update(bytes, 0, bytes.length - Integer.BYTES);
            if (in.getInt(bytes.length - Integer.BYTES) != (int) crc.getValue()
                    || in.getInt() != MAGIC || in.getInt() != FORMAT || in.getLong() != expectedLsn) {
                return Optional.empty();
            }
            int pairs = in.getInt();
            int clearedCount = in.getInt();
            if ((long) HEADER + pairs * 16L + clearedCount * 8L + Integer.BYTES != bytes.length) {
                return Optional.empty();
            }
            long[] counts = new long[pairs * 2];
            for (int i = 0; i < counts.length; i++) {
                counts[i] = in.getLong();
            }
            long[] cleared = new long[clearedCount];
            for (int i = 0; i < clearedCount; i++) {
                cleared[i] = in.getLong();
            }
            return Optional.of(new Contents(counts, cleared));
        } catch (IOException e) {
            throw HStoreException.io("cannot read reference journal " + path, e);
        }
    }

    static void clear(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (FileChannel channel = FileHandle.channel(path, StandardOpenOption.WRITE)) {
            channel.truncate(0);
            channel.force(true);
        } catch (IOException e) {
            throw HStoreException.io("cannot clear reference journal " + path, e);
        }
    }

    private static final class Writer {
        private final FileChannel channel;
        private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER).order(ByteOrder.LITTLE_ENDIAN);
        private final CRC32C crc = new CRC32C();

        Writer(FileChannel channel) {
            this.channel = channel;
        }

        Writer putInt(int value) {
            room(Integer.BYTES);
            buffer.putInt(value);
            return this;
        }

        Writer putLong(long value) {
            room(Long.BYTES);
            buffer.putLong(value);
            return this;
        }

        void finish() throws IOException {
            flush();
            buffer.putInt((int) crc.getValue());
            buffer.flip();
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }

        private void room(int bytes) {
            if (buffer.remaining() < bytes) {
                try {
                    flush();
                } catch (IOException e) {
                    throw HStoreException.io("cannot write reference journal", e);
                }
            }
        }

        private void flush() throws IOException {
            buffer.flip();
            crc.update(buffer.duplicate());
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            buffer.clear();
        }
    }
}
