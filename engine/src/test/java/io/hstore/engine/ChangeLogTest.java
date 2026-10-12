// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.catalog.Branch;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.TxnOptions;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeLogTest {

    @TempDir
    Path directory;

    private static EngineOptions options() {
        return EngineTest.small().withPagesPerSegment(64).withHistoryLimit(100_000).withCheckpointWalBytes(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4})
    void replayingEachCommitsChangesRebuildsItsTrees(long seed) {
        Random random = new Random(seed);
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            Map<Long, EdgeKind> edges = new HashMap<>();
            List<Long> nodes = new ArrayList<>();
            long first = engine.stats().generation();
            int branchChanges = 0;
            for (int round = 0; round < 160; round++) {
                branchChanges += step(engine, random, edges, nodes);
            }
            long commits = engine.stats().generation() - first - branchChanges;
            assertEquals(commits, engine.verifyChangeLog().commits());
            assertTrue(commits > 150);
        }
    }

    private static int step(StorageEngine engine, Random random, Map<Long, EdgeKind> edges, List<Long> nodes) {
        List<Long> known = new ArrayList<>(edges.keySet());
        int choice = random.nextInt(12);
        if (choice == 0 || known.size() < 3) {
            EdgeKind kind = random.nextBoolean() ? EdgeKind.SET : EdgeKind.ORDERED;
            edges.put(engine.write(txn -> txn.createEdge(EngineTest.EDGE, kind)), kind);
            return 0;
        }
        long edge = known.get(random.nextInt(known.size()));
        EdgeKind kind = edges.get(edge);
        switch (choice) {
            case 1 -> {
                String key = "n" + random.nextInt(1_000_000);
                engine.write(txn -> {
                    nodes.add(txn.createNode(EngineTest.NODE, key));
                    return null;
                });
            }
            case 2 -> {
                engine.write(txn -> {
                    txn.delete(edge);
                    return null;
                });
                edges.remove(edge);
            }
            case 3 -> {
                int size = random.nextBoolean() ? 2_000 : 9_000;
                long start = 1_000_000L + random.nextInt(1_000_000);
                long target = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
                edges.put(target, EdgeKind.SET);
                engine.write(txn -> {
                    txn.load(target, LongStream.range(start, start + size).mapToObj(Incidence::of).toList());
                    return null;
                });
            }
            case 4 -> {
                try (Transaction abandoned = engine.begin()) {
                    abandoned.load(edge, LongStream.range(5_000_000, 5_003_000).mapToObj(Incidence::of).toList());
                }
            }
            case 5 -> {
                Branch branch = engine.createBranch("b" + engine.stats().generation(), Branch.MAIN);
                for (int i = 0; i < 3; i++) {
                    long member = 9_000_000L + random.nextInt(1_000_000);
                    engine.write(TxnOptions.defaults().onBranch(branch.id()), txn -> {
                        insert(txn, edge, kind, member, 0);
                        return null;
                    });
                }
                if (random.nextBoolean()) {
                    engine.dropBranch(branch.id());
                    return 2;
                }
                return 1;
            }
            case 6 -> engine.write(TxnOptions.defaults().withRequestId("r" + random.nextInt(50)), txn -> {
                insert(txn, edge, kind, 7_000_000L + random.nextInt(1_000_000), 0);
                return null;
            });
            case 7 -> {
                if (kind != EdgeKind.ORDERED) {
                    return 0;
                }
                engine.write(txn -> {
                    for (int i = 0; i < 40; i++) {
                        txn.insertAt(edge, 0, Incidence.of(8_000_000L + random.nextInt(1_000_000)));
                    }
                    return null;
                });
            }
            default -> engine.write(txn -> {
                for (int i = 0; i < 1 + random.nextInt(60); i++) {
                    long member = random.nextInt(20_000);
                    if (kind == EdgeKind.ORDERED) {
                        long size = txn.requireEdge(edge).size();
                        if (size > 0 && random.nextInt(3) == 0) {
                            txn.removeAt(edge, random.nextLong(size));
                        } else {
                            txn.insertAt(edge, size == 0 ? 0 : random.nextLong(size + 1), Incidence.of(member).withWeight(random.nextInt(5)));
                        }
                    } else if (txn.contains(edge, member)) {
                        txn.remove(edge, member);
                    } else {
                        txn.insert(edge, Incidence.of(member).withWeight(random.nextInt(5)));
                    }
                }
                return null;
            });
        }
        return 0;
    }

    private static void insert(Transaction txn, long edge, EdgeKind kind, long member, long index) {
        if (kind == EdgeKind.ORDERED) {
            txn.insertAt(edge, index, Incidence.of(member));
        } else if (!txn.contains(edge, member)) {
            txn.insert(edge, member);
        }
    }
}
