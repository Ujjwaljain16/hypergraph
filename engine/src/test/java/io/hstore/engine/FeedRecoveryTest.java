// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.catalog.Slot;
import io.hstore.engine.feed.CommitEvent;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeedRecoveryTest {

    private static final int COMMITS = 20;

    @TempDir
    Path directory;

    private final AtomicInteger decoded = new AtomicInteger();

    private final Slot<Long> counted = Slot.primary(122, "counted", new TreeSchema<>(242, "counted", FingerprintMode.SET,
            ValueCodec.rows(ByteCursor::signedVarLongSize, ByteCursor::putSignedVarLong, in -> {
                decoded.incrementAndGet();
                return in.getSignedVarLong();
            }),
            (key, value, into) -> into.entry(key, Hashing.of(key, value))));

    private EngineOptions options() {
        return EngineTest.small().withCheckpointWalBytes(Long.MAX_VALUE).withExtensions(List.of(counted), List.of());
    }

    private List<CommitEvent> crashAfterCommits() {
        StorageEngine engine = StorageEngine.open(directory, options());
        for (long key = 1; key <= COMMITS; key++) {
            long value = key * 10;
            long written = key;
            engine.write(txn -> {
                txn.put(counted, written, value);
                return null;
            });
        }
        List<CommitEvent> events = engine.feed().replay(0).toList();
        assertEquals(COMMITS, events.stream().filter(event -> !event.slots().isEmpty()).count());
        engine.halt();
        return events;
    }

    @Test
    void recoveryDecodesEachLoggedValueOnce() {
        List<CommitEvent> events = crashAfterCommits();
        decoded.set(0);
        try (StorageEngine recovered = StorageEngine.open(directory, options())) {
            assertEquals(COMMITS, recovered.recovery().replayedCommits());
            assertEquals(COMMITS, decoded.get());
            assertEquals(events, recovered.feed().replay(0).toList());
        }
    }

    @Test
    void recoveryRestoresFeedEventsTheFeedLost() throws IOException {
        List<CommitEvent> events = crashAfterCommits();
        try (Stream<Path> files = Files.list(directory.resolve("feed"))) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        try (StorageEngine recovered = StorageEngine.open(directory, options())) {
            assertEquals(events, recovered.feed().replay(0).toList());
            for (long key = 1; key <= COMMITS; key++) {
                long read = key;
                assertEquals(Optional.of(key * 10), recovered.read(snapshot -> snapshot.get(counted, read)));
            }
        }
    }
}
