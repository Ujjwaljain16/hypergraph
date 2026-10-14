// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.feed;

import io.hstore.engine.catalog.SlotRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChangeFeedTest {

    @TempDir
    Path directory;

    @Test
    void replayReadsOnlyTheHeaderOfAFrameItSkips() throws IOException {
        FeedCodec codec = new FeedCodec(new SlotRegistry(List.of()));
        try (ChangeFeed feed = ChangeFeed.open(directory, codec)) {
            for (long generation = 1; generation <= 5; generation++) {
                feed.append(new CommitEvent(generation, generation, generation * 1000, 0, List.of(), List.of()));
            }
            Path file;
            try (Stream<Path> files = Files.list(directory)) {
                file = files.filter(path -> path.toString().endsWith(".feed")).findFirst().orElseThrow();
            }
            try (RandomAccessFile raw = new RandomAccessFile(file.toFile(), "rw")) {
                long second = 16 + frameLength(raw, 0);
                long payload = second + 16;
                raw.seek(payload);
                int value = raw.read();
                raw.seek(payload);
                raw.write(value ^ 0xFF);
            }
            assertEquals(List.of(3L, 4L, 5L), feed.replay(2).map(CommitEvent::generation).toList());
            assertEquals(List.of(1L), feed.replay(0).map(CommitEvent::generation).toList());
        }
    }

    private static int frameLength(RandomAccessFile raw, long at) throws IOException {
        byte[] header = new byte[4];
        raw.seek(at);
        raw.readFully(header);
        return ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
