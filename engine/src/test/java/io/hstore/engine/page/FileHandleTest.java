// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileHandleTest {

    @TempDir
    Path directory;

    @Test
    void aThreadInterruptedBeforehandWritesAndKeepsItsInterrupt() throws Exception {
        Path path = directory.resolve("before");
        try (FileHandle file = FileHandle.open(path)) {
            Thread.currentThread().interrupt();
            try {
                write(file, 0, "first");
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            write(file, 5, "second");
            assertEquals("firstsecond", read(file, 11));
        }
        assertEquals("firstsecond", Files.readString(path));
    }

    @Test
    void anInterruptDuringACallReopensTheFileAndRetries() throws Exception {
        try (FileHandle file = FileHandle.open(directory.resolve("during"))) {
            write(file, 0, "kept");
            AtomicInteger attempts = new AtomicInteger();
            try {
                String text = file.call(channel -> {
                    if (attempts.getAndIncrement() == 0) {
                        Thread.currentThread().interrupt();
                    }
                    ByteBuffer buffer = ByteBuffer.allocate(4);
                    channel.read(buffer, 0);
                    return new String(buffer.array(), StandardCharsets.UTF_8);
                });
                assertEquals("kept", text);
                assertEquals(2, attempts.get());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            write(file, 4, "!");
            assertEquals("kept!", read(file, 5));
        }
    }

    @Test
    void aChannelClosedUnderAnotherThreadIsReopenedForIt() throws Exception {
        try (FileHandle file = FileHandle.open(directory.resolve("other"))) {
            write(file, 0, "data");
            AtomicInteger attempts = new AtomicInteger();
            long size = file.call(channel -> {
                if (attempts.getAndIncrement() == 0) {
                    Thread closer = Thread.ofPlatform().start(() -> {
                        try {
                            channel.close();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    try {
                        closer.join();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return channel.size();
            });
            assertEquals(4, size);
            assertEquals(2, attempts.get());
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }

    @Test
    void aClosedHandleStaysClosed() throws Exception {
        FileHandle file = FileHandle.open(directory.resolve("closed"));
        file.close();
        assertThrows(ClosedChannelException.class, file::size);
    }

    private static void write(FileHandle file, long position, String text) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        file.run(channel -> {
            while (buffer.hasRemaining()) {
                channel.write(buffer, position + buffer.position());
            }
        });
    }

    private static String read(FileHandle file, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        file.run(channel -> channel.read(buffer, 0));
        return new String(buffer.array(), StandardCharsets.UTF_8);
    }
}
