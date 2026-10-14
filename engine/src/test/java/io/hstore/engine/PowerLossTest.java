// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.feed.CommitEvent;
import io.hstore.engine.page.PowerLoss;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.tree.TreeVerifier;
import io.hstore.engine.txn.Durability;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.WalMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerLossTest {

    private static final int STEPS = 16;
    private static final int CRASHES = 4;
    private static final int SECOND_CRASH_ODDS = 80;

    @TempDir
    Path directory;

    private int runs;

    private record Commit(String key, long node, long bulk, int members, long dropped) {
    }

    private static final class History {
        final List<Commit> commits = new ArrayList<>();
        final List<String> abandoned = new ArrayList<>();
        long edge;
        int acknowledged;
        int checkpointed;
    }

    static Stream<Arguments> configurations() {
        long seeds = Long.getLong("hstore.powerloss.seeds", 1);
        return Stream.of(Durability.values()).flatMap(durability -> Stream.of(WalMode.values()).flatMap(mode ->
                LongStream.rangeClosed(1, seeds).mapToObj(seed -> Arguments.of(durability, mode, seed))));
    }

    @ParameterizedTest
    @MethodSource("configurations")
    void powerCutsAtRandomEventsRecoverAPrefixOfTheCommits(Durability durability, WalMode mode, long seed) {
        EngineOptions options = options(durability, mode);
        long events = dryRun(options, seed);
        Random random = new Random(seed * 31 + durability.ordinal() * 7 + mode.ordinal());
        for (int crash = 0; crash < CRASHES; crash++) {
            long event = 1 + random.nextLong(events + 1);
            PowerLoss.Tear tear = tear(random);
            String context = "%s %s seed %d, power lost at event %d of %d, unsynced writes %s".formatted(durability, mode, seed, event, events, tear);
            Path database = directory.resolve("run-" + runs++);
            History history = new History();
            try {
                try (PowerLoss loss = PowerLoss.watch(database)) {
                    loss.crashAt(event);
                    drive(database, options, loss, history, seed);
                    loss.restore(tear, random);
                }
                recoverTwice(database, options, history, durability, random, context);
            } catch (RuntimeException failure) {
                throw new AssertionError(context, failure);
            }
        }
    }

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

    @ParameterizedTest
    @EnumSource(WalMode.class)
    void asyncCommitsAfterTheLastCheckpointAreLostTogether(WalMode mode) {
        EngineOptions options = options(Durability.ASYNC, mode);
        try (PowerLoss loss = PowerLoss.watch(directory)) {
            StorageEngine engine = StorageEngine.open(directory, options);
            for (int i = 0; i < 10; i++) {
                String key = "n" + i;
                engine.write(txn -> txn.createNode(EngineTest.NODE, key));
                if (i == 4) {
                    engine.checkpoint();
                }
            }
            loss.powerOff();
            engine.halt();
            loss.restore(PowerLoss.Tear.NOTHING, new Random(1));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            engine.read(snapshot -> {
                assertEquals(5, snapshot.countOfType(0, EngineTest.NODE));
                IntStream.range(0, 5).forEach(i -> assertTrue(snapshot.resolve(0, EngineTest.NODE, "n" + i).isPresent()));
                return null;
            });
            engine.verifyReferences();
        }
    }

    @ParameterizedTest
    @EnumSource(value = PowerLoss.Tear.class, names = {"HALF_LAST", "FLIP_LAST"})
    void aTornOrCorruptCommitRecordDropsOnlyTheUnacknowledgedCommit(PowerLoss.Tear tear) {
        EngineOptions options = options(Durability.SYNC, WalMode.PAGE_REFERENCES);
        try (PowerLoss loss = PowerLoss.watch(directory)) {
            StorageEngine engine = StorageEngine.open(directory, options);
            engine.write(txn -> txn.createNode(EngineTest.NODE, "acknowledged"));
            loss.crashOn(event -> event.kind() == PowerLoss.Kind.FORCE && event.file().toString().endsWith(".wal"));
            assertThrows(PowerLoss.Cut.class, () -> engine.write(txn -> txn.createNode(EngineTest.NODE, "unacknowledged")));
            engine.halt();
            loss.restore(file -> file.toString().endsWith(".wal") ? tear : PowerLoss.Tear.ALL, new Random(1));
        }
        reopenWithOnly("acknowledged", options);
    }

    @ParameterizedTest
    @EnumSource(value = PowerLoss.Tear.class, names = {"HALF_LAST", "FLIP_LAST"})
    void aCommitWhosePageWasTornIsDroppedEvenThoughItsLogRecordsSurvived(PowerLoss.Tear tear) {
        EngineOptions options = options(Durability.SYNC, WalMode.PAGE_REFERENCES);
        try (PowerLoss loss = PowerLoss.watch(directory)) {
            StorageEngine engine = StorageEngine.open(directory, options);
            engine.write(txn -> txn.createNode(EngineTest.NODE, "acknowledged"));
            loss.crashOn(event -> event.kind() == PowerLoss.Kind.FORCE && event.file().toString().endsWith(".seg"));
            assertThrows(PowerLoss.Cut.class, () -> engine.write(txn -> {
                txn.createNode(EngineTest.NODE, "unacknowledged");
                long edge = txn.createEdge(EngineTest.EDGE, EdgeKind.SET);
                txn.load(edge, LongStream.range(0, 6_000).mapToObj(Incidence::of).toList());
                return edge;
            }));
            engine.halt();
            loss.restore(file -> file.toString().endsWith(".seg") ? tear : PowerLoss.Tear.ALL, new Random(1));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            assertEquals(1, engine.recovery().discardedCommits());
        }
        reopenWithOnly("acknowledged", options);
    }

    private void reopenWithOnly(String key, EngineOptions options) {
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            engine.read(snapshot -> {
                assertTrue(snapshot.resolve(0, EngineTest.NODE, key).isPresent());
                assertEquals(1, snapshot.countOfType(0, EngineTest.NODE));
                return null;
            });
            engine.verifyReferences();
            engine.write(txn -> txn.createNode(EngineTest.NODE, "later"));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            assertEquals(2L, (long) engine.read(snapshot -> snapshot.countOfType(0, EngineTest.NODE)));
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

    private long dryRun(EngineOptions options, long seed) {
        Path database = directory.resolve("run-" + runs++);
        try (PowerLoss loss = PowerLoss.watch(database)) {
            return drive(database, options, loss, new History(), seed);
        }
    }

    private static long drive(Path database, EngineOptions options, PowerLoss loss, History history, long seed) {
        StorageEngine engine = null;
        long events;
        try {
            engine = StorageEngine.open(database, options);
            workload(engine, loss, history, seed);
        } catch (Throwable failure) {
            if (!loss.crashed()) {
                throw failure;
            }
        } finally {
            events = loss.events();
            loss.powerOff();
            if (engine != null) {
                engine.halt();
            }
        }
        return events;
    }

    private static void workload(StorageEngine engine, PowerLoss loss, History history, long seed) {
        Random random = new Random(seed);
        for (int step = 0; step < STEPS && !loss.crashed(); step++) {
            int roll = step == 0 ? 0 : random.nextInt(100);
            try {
                if (roll < 50) {
                    commit(engine, loss, history, random, 0, false);
                } else if (roll < 65) {
                    commit(engine, loss, history, random, random.nextBoolean() ? 300 : 5_000, false);
                } else if (roll < 73) {
                    commit(engine, loss, history, random, 0, true);
                } else if (roll < 80) {
                    abandon(engine, history, random.nextBoolean() ? 0 : 5_000);
                } else if (roll < 92) {
                    engine.checkpoint();
                    if (!loss.crashed()) {
                        history.checkpointed = history.acknowledged;
                    }
                } else {
                    engine.compact();
                    if (!loss.crashed()) {
                        history.checkpointed = history.acknowledged;
                    }
                }
            } catch (Throwable failure) {
                if (!loss.crashed()) {
                    throw failure;
                }
            }
        }
    }

    private static void commit(StorageEngine engine, PowerLoss loss, History history, Random random, int members, boolean drop) {
        int index = history.commits.size();
        List<Commit> alive = alive(history.commits, index);
        long victim = drop && !alive.isEmpty() ? alive.get(random.nextInt(alive.size())).bulk() : 0;
        try (Transaction txn = engine.begin()) {
            String key = "c-" + index;
            long node = txn.createNode(EngineTest.NODE, key);
            if (index == 0) {
                history.edge = txn.createEdge(EngineTest.EDGE, EdgeKind.ORDERED);
            }
            txn.append(history.edge, Incidence.of(node));
            long bulk = 0;
            if (members > 0) {
                bulk = txn.createEdge(EngineTest.EDGE, EdgeKind.SET);
                long first = 1_000_000L + index * 10_000L;
                txn.load(bulk, LongStream.range(first, first + members).mapToObj(Incidence::of).toList());
            }
            if (victim != 0) {
                txn.delete(victim);
            }
            history.commits.add(new Commit(key, node, bulk, members, victim));
            txn.commit();
        }
        if (!loss.crashed()) {
            history.acknowledged = history.commits.size();
        }
    }

    private static void abandon(StorageEngine engine, History history, int members) {
        String key = "x-" + history.abandoned.size();
        history.abandoned.add(key);
        try (Transaction txn = engine.begin()) {
            txn.createNode(EngineTest.NODE, key);
            if (members > 0) {
                long bulk = txn.createEdge(EngineTest.EDGE, EdgeKind.SET);
                txn.load(bulk, LongStream.range(5_000_000L, 5_000_000L + members).mapToObj(Incidence::of).toList());
            }
            txn.abort();
        }
    }

    private static List<Commit> alive(List<Commit> commits, int prefix) {
        List<Commit> alive = new ArrayList<>();
        for (Commit commit : commits.subList(0, prefix)) {
            if (commit.dropped() != 0) {
                alive.removeIf(earlier -> earlier.bulk() == commit.dropped());
            }
            if (commit.bulk() != 0) {
                alive.add(commit);
            }
        }
        return alive;
    }

    private static void recoverTwice(Path database, EngineOptions options, History history, Durability durability, Random random,
                                     String context) {
        PowerLoss.Tear tear = tear(random);
        Random chance = new Random(random.nextLong());
        int observed = -1;
        boolean acknowledged = false;
        boolean checkpointed = false;
        try (PowerLoss loss = PowerLoss.watch(database)) {
            loss.crashOn(_ -> chance.nextInt(SECOND_CRASH_ODDS) == 0);
            StorageEngine engine = null;
            try {
                engine = StorageEngine.open(database, options);
                observed = verify(engine, history, durability, context);
                engine.write(txn -> txn.createNode(EngineTest.NODE, "after"));
                acknowledged = !loss.crashed();
                engine.checkpoint();
                checkpointed = !loss.crashed();
            } catch (Throwable failure) {
                if (!loss.crashed()) {
                    throw failure;
                }
            } finally {
                loss.powerOff();
                if (engine != null) {
                    engine.halt();
                }
            }
            loss.restore(tear, random);
        }
        String again = context + ", recovered and lost power again with unsynced writes " + tear;
        try (StorageEngine engine = StorageEngine.open(database, options)) {
            int recovered = verify(engine, history, durability, again);
            if (observed >= 0) {
                assertEquals(observed, recovered, again + ": commits seen after the first recovery");
            }
            if (checkpointed || acknowledged && durability == Durability.SYNC) {
                assertTrue(engine.read(snapshot -> snapshot.resolve(0, EngineTest.NODE, "after")).isPresent(), again + ": commit after recovery");
            }
        }
    }

    private static PowerLoss.Tear tear(Random random) {
        return PowerLoss.Tear.values()[random.nextInt(PowerLoss.Tear.values().length)];
    }

    private static int verify(StorageEngine engine, History history, Durability durability, String context) {
        int recovered = engine.read(snapshot -> {
            int present = 0;
            while (snapshot.resolve(0, EngineTest.NODE, "c-" + present).isPresent()) {
                present++;
            }
            for (int i = present; i <= history.commits.size(); i++) {
                assertTrue(snapshot.resolve(0, EngineTest.NODE, "c-" + i).isEmpty(), context + ": c-" + i + " survived without c-" + present);
            }
            for (String key : history.abandoned) {
                assertTrue(snapshot.resolve(0, EngineTest.NODE, key).isEmpty(), context + ": abandoned " + key + " appeared");
            }
            int after = snapshot.resolve(0, EngineTest.NODE, "after").isPresent() ? 1 : 0;
            assertEquals(present + after, snapshot.countOfType(0, EngineTest.NODE), context + ": node count");
            assertEquals(LongStream.rangeClosed(1, present + after).boxed().toList(),
                    engine.feed().replay(0).map(CommitEvent::generation).toList(), context + ": change feed generations");
            return present;
        });
        int floor = durability == Durability.SYNC ? history.acknowledged : history.checkpointed;
        assertTrue(recovered >= floor, context + ": recovered " + recovered + " commits, " + floor + " were durable ("
                + history.acknowledged + " acknowledged, " + history.checkpointed + " checkpointed)");
        assertTrue(recovered <= history.commits.size(), context + ": recovered " + recovered + " of " + history.commits.size() + " attempted");
        engine.read(snapshot -> {
            List<Commit> prefix = history.commits.subList(0, recovered);
            if (recovered > 0) {
                assertEquals(prefix.stream().map(Commit::node).toList(),
                        snapshot.requireEdge(history.edge).stream().map(Incidence::member).toList(), context + ": ordered edge");
            }
            for (Commit commit : prefix) {
                assertEquals(commit.node(), snapshot.resolve(0, EngineTest.NODE, commit.key()).orElseThrow(), context);
                assertEquals(1, snapshot.degree(commit.node()), context + ": degree of " + commit.key());
            }
            List<Commit> alive = alive(history.commits, recovered);
            for (Commit commit : history.commits) {
                if (commit.bulk() == 0) {
                    continue;
                }
                if (alive.contains(commit)) {
                    assertEquals(commit.members(), snapshot.requireEdge(commit.bulk()).size(), context + ": members of " + commit.key());
                } else {
                    assertTrue(snapshot.edge(commit.bulk()).isEmpty(), context + ": edge of " + commit.key() + " should not exist");
                }
            }
            return null;
        });
        verifyTrees(engine);
        engine.verifyReferences();
        return recovered;
    }

    private static void verifyTrees(StorageEngine engine) {
        try (Snapshot snapshot = engine.snapshot()) {
            TreeVerifier verifier = new TreeVerifier(engine.source());
            for (Branch branch : snapshot.catalog().branches().values()) {
                if (branch.isActive()) {
                    for (Map.Entry<Integer, ?> root : branch.roots().roots().entrySet()) {
                        Slot<?> slot = engine.slots().slot(root.getKey());
                        verifier.verify(branch.roots().get(slot), slot.schema());
                    }
                }
            }
        }
    }

    private static EngineOptions options(Durability durability, WalMode mode) {
        return EngineTest.small().withDurability(durability).withWalMode(mode).withWalSegmentBytes(48 << 10)
                .withCheckpointWalBytes(Long.MAX_VALUE).withMaintenanceInterval(Duration.ofHours(1));
    }
}
