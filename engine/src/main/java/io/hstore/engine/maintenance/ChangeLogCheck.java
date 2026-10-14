// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.maintenance;

import io.hstore.engine.catalog.Generation;
import io.hstore.engine.catalog.RootVector;
import io.hstore.engine.catalog.SlotRegistry;
import io.hstore.engine.tree.NodeSource;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeDiff;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.WriteScope;
import io.hstore.engine.txn.ChangeLog;
import io.hstore.engine.wal.WalRecord;
import io.hstore.engine.wal.WriteAheadLog;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;

public final class ChangeLogCheck {

    public record Result(int commits) {
    }

    private ChangeLogCheck() {
    }

    public static Result verify(WriteAheadLog wal, Collection<Generation> history, SlotRegistry slots, NodeSource source) {
        Map<Long, Generation> generations = new HashMap<>();
        history.forEach(generation -> generations.put(generation.id(), generation));
        Map<Long, WalRecord.Changes> open = new HashMap<>();
        int verified = 0;
        try (Stream<WriteAheadLog.Positioned> records = wal.read(0)) {
            for (Iterator<WriteAheadLog.Positioned> it = records.iterator(); it.hasNext(); ) {
                switch (it.next().record()) {
                    case WalRecord.Changes changes -> open.put(changes.txnId(), changes);
                    case WalRecord.Abort abort -> open.remove(abort.txnId());
                    case WalRecord.Commit commit -> {
                        WalRecord.Changes changes = open.remove(commit.txnId());
                        Generation after = generations.get(commit.generation());
                        Generation before = generations.get(commit.generation() - 1);
                        if (changes != null && after != null && before != null) {
                            RootVector replayed = ChangeLog.apply(before.branch(changes.branch()).roots(), changes.payload(), slots, source, new WriteScope());
                            compare(commit.generation(), slots, source, replayed, after.branch(changes.branch()).roots());
                            verified++;
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        return new Result(verified);
    }

    private static void compare(long generation, SlotRegistry slots, NodeSource source, RootVector replayed, RootVector expected) {
        TreeSet<Integer> ids = new TreeSet<>(replayed.roots().keySet());
        ids.addAll(expected.roots().keySet());
        for (int id : ids) {
            TreeSchema<?> schema = slots.slot(id).schema();
            difference(schema, source, replayed, expected, id).ifPresent(difference -> {
                throw new IllegalStateException("generation " + generation + ", slot " + slots.slot(id).name()
                        + ": replaying the change log gives a different tree, " + difference);
            });
        }
    }

    private static <V> Optional<String> difference(TreeSchema<V> schema, NodeSource source, RootVector replayed, RootVector expected, int id) {
        return TreeDiff.difference(new Tree<>(schema, source, replayed.get(id)), new Tree<>(schema, source, expected.get(id)));
    }
}
