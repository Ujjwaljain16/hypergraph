// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerLossModelTest {

    @TempDir
    Path directory;

    @Test
    void onlyForcedBytesSurvive() throws Exception {
        Path file = directory.resolve("log");
        try (PowerLoss loss = PowerLoss.watch(directory)) {
            try (FileHandle handle = FileHandle.open(file)) {
                FileHandle.syncDirectory(directory);
                write(handle, 0, "synced");
                handle.run(channel -> channel.force(false));
                write(handle, 6, "-lost");
            }
            loss.restore(PowerLoss.Tear.NOTHING, new Random(1));
        }
        assertEquals("synced", Files.readString(file));
    }

    @Test
    void theLastUnsyncedWriteCanBeCutInHalfOrFlipped() throws Exception {
        Path half = directory.resolve("half");
        Path flipped = directory.resolve("flipped");
        for (PowerLoss.Tear tear : new PowerLoss.Tear[]{PowerLoss.Tear.HALF_LAST, PowerLoss.Tear.FLIP_LAST}) {
            Path file = tear == PowerLoss.Tear.HALF_LAST ? half : flipped;
            try (PowerLoss loss = PowerLoss.watch(directory)) {
                try (FileHandle handle = FileHandle.open(file)) {
                    FileHandle.syncDirectory(directory);
                    write(handle, 0, "first");
                    write(handle, 5, "12345678");
                }
                loss.restore(tear, new Random(1));
            }
        }
        assertEquals("first1234", Files.readString(half));
        byte[] expected = "first12345678".getBytes(StandardCharsets.UTF_8);
        expected[9] ^= 0x5A;
        assertArrayEquals(expected, Files.readAllBytes(flipped));
    }

    @Test
    void aFileWhoseDirectoryWasNeverSyncedDisappears() throws Exception {
        Path file = directory.resolve("new");
        try (PowerLoss loss = PowerLoss.watch(directory)) {
            try (FileHandle handle = FileHandle.open(file)) {
                write(handle, 0, "data");
                handle.run(channel -> channel.force(false));
            }
            loss.restore(PowerLoss.Tear.NOTHING, new Random(1));
        }
        assertFalse(Files.exists(file));
    }

    @Test
    void aFileRenamedAfterItWasForcedKeepsItsContents() throws Exception {
        Path temporary = directory.resolve("manifest.tmp");
        Path target = directory.resolve("manifest");
        Path unsynced = directory.resolve("written-elsewhere");
        try (PowerLoss loss = PowerLoss.watch(directory)) {
            try (FileChannel channel = FileHandle.channel(temporary, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap("generation".getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
            Files.move(temporary, target);
            FileHandle.syncDirectory(directory);
            Files.writeString(unsynced, "never forced");
            loss.restore(PowerLoss.Tear.NOTHING, new Random(1));
        }
        assertEquals("generation", Files.readString(target));
        assertEquals("", Files.readString(unsynced));
    }

    @Test
    void mappedFilesKeepWhatTheirLastForceSaw() throws Exception {
        Path file = directory.resolve("pages.dir");
        try (PowerLoss loss = PowerLoss.watch(directory)) {
            PageDirectory addresses = PageDirectory.open(file);
            FileHandle.syncDirectory(directory);
            addresses.put(1, 11);
            addresses.force();
            addresses.put(2, 22);
            addresses.close();
            loss.restore(PowerLoss.Tear.NOTHING, new Random(1));
        }
        PageDirectory reopened = PageDirectory.open(file);
        assertEquals(11, reopened.get(1));
        assertEquals(0, reopened.get(2));
        reopened.close();
    }

    @Test
    void theChosenEventCutsThePowerAndLaterWritesAreNotRecorded() throws Exception {
        Path file = directory.resolve("log");
        try (PowerLoss loss = PowerLoss.watch(directory)) {
            loss.crashOn(event -> event.kind() == PowerLoss.Kind.FORCE);
            try (FileHandle handle = FileHandle.open(file)) {
                FileHandle.syncDirectory(directory);
                write(handle, 0, "unsynced");
                assertThrows(PowerLoss.Cut.class, () -> handle.run(channel -> channel.force(false)));
                assertTrue(loss.crashed());
                write(handle, 8, "-after");
                handle.run(channel -> channel.force(false));
            }
            loss.restore(PowerLoss.Tear.NOTHING, new Random(1));
        }
        assertEquals("", Files.readString(file));
    }

    private static void write(FileHandle handle, long position, String text) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        handle.run(channel -> channel.write(buffer, position));
    }
}
