// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.feed.CommitEvent;
import io.hstore.engine.page.PowerLoss;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.txn.Durability;
import io.hstore.engine.txn.WalMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerLossTest {

    @TempDir
    Path directory;

    @Test
    void aDatabaseCreatedJustBeforeAPowerLossStillOpens() {
        EngineOptions options = options(Durability.SYNC, WalMode.PAGE_REFERENCES);
        try (PowerLoss loss = PowerLoss.watch(directory)) {
            StorageEngine engine = StorageEngine.open(directory, options);
            engine.write(txn -> txn.createNode(EngineTest.NODE, "acknowledged"));
            loss.powerOff();
            engine.halt();
            loss.restore(PowerLoss.Tear.NOTHING, new Random(1));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            assertTrue(engine.read(snapshot -> snapshot.resolve(0, EngineTest.NODE, "acknowledged")).isPresent());
        }
    }

    @ParameterizedTest
    @EnumSource(WalMode.class)
    void pagesInASegmentCreatedSinceTheLastCheckpointSurvive(WalMode mode) {
        EngineOptions options = options(Durability.SYNC, mode);
        long edge;
        try (PowerLoss loss = PowerLoss.watch(directory)) {
            StorageEngine engine = StorageEngine.open(directory, options);
            edge = engine.write(txn -> {
                long created = txn.createEdge(EngineTest.EDGE, EdgeKind.SET);
                txn.load(created, LongStream.range(0, 6_000).mapToObj(Incidence::of).toList());
                return created;
            });
            loss.powerOff();
            engine.halt();
            loss.restore(PowerLoss.Tear.NOTHING, new Random(1));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            assertEquals(0, engine.recovery().discardedCommits());
            assertEquals(6_000, (long) engine.read(snapshot -> snapshot.requireEdge(edge).size()));
            engine.verifyReferences();
        }
    }

    @Test
    void aPageDirectoryWhoseFirstPageNumberWasNeverForcedStillHandsOutNumbers() {
        EngineOptions options = options(Durability.SYNC, WalMode.PAGE_REFERENCES);
        try (PowerLoss loss = PowerLoss.watch(directory)) {
            loss.crashOn(event -> event.kind() == PowerLoss.Kind.MAPPED_FORCE && event.file().endsWith("pages.ref"));
            assertThrows(PowerLoss.Cut.class, () -> StorageEngine.open(directory, options));
            loss.restore(PowerLoss.Tear.NOTHING, new Random(1));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            engine.write(txn -> txn.createNode(EngineTest.NODE, "first"));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            assertTrue(engine.read(snapshot -> snapshot.resolve(0, EngineTest.NODE, "first")).isPresent());
            engine.verifyReferences();
        }
    }

    @Test
    void aFeedFrameWhoseUnsyncedGenerationWasDamagedDoesNotHideCommittedEvents() throws Exception {
        List<Long> frames = threeCommitsThenPowerLoss();
        try (FileChannel feed = FileChannel.open(feedSegment(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer generation = ByteBuffer.allocate(1);
            feed.read(generation, frames.get(2) + 10);
            generation.put(0, (byte) (generation.get(0) ^ 0x5A));
            feed.write(generation.rewind(), frames.get(2) + 10);
        }
        assertFeedHoldsOneEventPerCommit();
    }

    @Test
    void aZeroedFeedFrameBeforeASurvivingOneDoesNotRepeatEvents() throws Exception {
        List<Long> frames = threeCommitsThenPowerLoss();
        try (FileChannel feed = FileChannel.open(feedSegment(), StandardOpenOption.WRITE)) {
            feed.write(ByteBuffer.allocate(Math.toIntExact(frames.get(2) - frames.get(1))), frames.get(1));
        }
        assertFeedHoldsOneEventPerCommit();
    }

    private List<Long> threeCommitsThenPowerLoss() throws Exception {
        EngineOptions options = options(Durability.SYNC, WalMode.PAGE_REFERENCES);
        try (PowerLoss loss = PowerLoss.watch(directory)) {
            StorageEngine engine = StorageEngine.open(directory, options);
            for (int i = 0; i < 3; i++) {
                String key = "n" + i;
                engine.write(txn -> txn.createNode(EngineTest.NODE, key));
            }
            loss.powerOff();
            engine.halt();
            loss.restore(PowerLoss.Tear.ALL, new Random(1));
        }
        List<Long> frames = new ArrayList<>();
        try (FileChannel feed = FileChannel.open(feedSegment(), StandardOpenOption.READ)) {
            ByteBuffer length = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (long position = 0; position < feed.size(); position += 16 + length.getInt(0)) {
                frames.add(position);
                feed.read(length.clear(), position);
            }
        }
        assertEquals(3, frames.size());
        return frames;
    }

    private Path feedSegment() {
        return directory.resolve("feed").resolve("%016x.feed".formatted(0));
    }

    private void assertFeedHoldsOneEventPerCommit() {
        try (StorageEngine engine = StorageEngine.open(directory, options(Durability.SYNC, WalMode.PAGE_REFERENCES))) {
            assertEquals(3L, (long) engine.read(snapshot -> snapshot.countOfType(0, EngineTest.NODE)));
            assertEquals(List.of(1L, 2L, 3L), engine.feed().replay(0).map(CommitEvent::generation).toList());
        }
    }

    private static EngineOptions options(Durability durability, WalMode mode) {
        return EngineTest.small().withDurability(durability).withWalMode(mode).withWalSegmentBytes(48 << 10)
                .withCheckpointWalBytes(Long.MAX_VALUE).withMaintenanceInterval(Duration.ofHours(1));
    }
}
