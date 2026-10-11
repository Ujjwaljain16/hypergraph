// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.maintenance;

import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.CatalogImage;
import io.hstore.engine.catalog.CatalogStore;
import io.hstore.engine.catalog.Generation;
import io.hstore.engine.catalog.SlotRegistry;
import io.hstore.engine.feed.ChangeFeed;
import io.hstore.engine.page.PageStore;
import io.hstore.engine.tree.Epoch;
import io.hstore.engine.tree.Materializer;
import io.hstore.engine.tree.Node;
import io.hstore.engine.tree.PagedNodeSource;
import io.hstore.engine.txn.CrashPoint;
import io.hstore.engine.txn.TransactionManager;
import io.hstore.engine.wal.WalRecord;
import io.hstore.engine.wal.WriteAheadLog;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.LongStream;

public final class Checkpointer {

    public record Result(long generation, long lsn, int walSegments) {
    }

    private final TransactionManager transactions;
    private final PageStore pages;
    private final PagedNodeSource source;
    private final WriteAheadLog wal;
    private final ChangeFeed feed;
    private final CatalogStore catalog;
    private final ReferenceCounter references;
    private final SlotRegistry slots;
    private final CrashPoint.Injector faults;
    private volatile long lastLsn;
    private volatile long completed;
    private long unapplied = -1;
    private Generation materialized;

    public Checkpointer(TransactionManager transactions, PageStore pages, PagedNodeSource source, WriteAheadLog wal, ChangeFeed feed,
                        CatalogStore catalog, ReferenceCounter references, SlotRegistry slots, CrashPoint.Injector faults,
                        Generation checkpointed, long recoveredLsn) {
        this.transactions = transactions;
        this.pages = pages;
        this.source = source;
        this.wal = wal;
        this.feed = feed;
        this.catalog = catalog;
        this.references = references;
        this.slots = slots;
        this.faults = faults;
        this.materialized = checkpointed;
        this.lastLsn = recoveredLsn;
    }

    public long walSinceCheckpoint() {
        return wal.end() - lastLsn;
    }

    public long completed() {
        return completed;
    }

    public Generation materialized() {
        return materialized;
    }

    public Result checkpoint() {
        Generation target = transactions.current();
        Epoch epoch = new Epoch();
        LongStream.Builder written = LongStream.builder();
        Generation stored;
        try {
            stored = materialize(target, epoch, written);
            faults.reach(CrashPoint.CHECKPOINT_PAGES);
            pages.checkpoint();
        } catch (RuntimeException | Error failure) {
            references.discarded(written.build().toArray());
            throw failure;
        }
        references.pause();
        try {
            Result result = publish(target, stored, epoch);
            faults.reach(CrashPoint.CHECKPOINT_JOURNAL);
            pages.applyJournal();
            unapplied = -1;
            lastLsn = result.lsn();
            completed++;
            return result;
        } finally {
            references.resume();
        }
    }

    private Generation materialize(Generation target, Epoch epoch, LongStream.Builder written) {
        Materializer materializer = new Materializer(new Materializer.Sink() {
            @Override
            public long allocate(int length) {
                long pageId = pages.allocate(length);
                written.add(pageId);
                return pageId;
            }

            @Override
            public void accept(long pageId, MemorySegment image, Node frozen) {
                pages.write(pageId, image);
                source.admit(pageId, frozen);
            }
        }, target.id(), pages.pageSize(), epoch, transactions.publishedEpoch());
        Map<Integer, Branch> branches = new TreeMap<>();
        target.branches().forEach((id, branch) -> branches.put(id, branch.withRoots(branch.roots().map((slot, ref) ->
                materializer.materialize(ref, slots.slot(slot).schema())))));
        return new Generation(target.id(), target.wallTime(), target.txnId(), target.nextAtom(), target.nextTxn(), branches);
    }

    private Result publish(Generation target, Generation stored, Epoch epoch) {
        if (unapplied >= 0) {
            pages.settleJournal(unapplied);
            unapplied = -1;
        }
        long lsn = target.id() == materialized.id() ? lastLsn : transactions.commitEnd(target.id());
        references.checkpointed(materialized, stored);
        materialized = stored;
        references.release(transactions.oldestReadable());
        pages.checkpoint();
        pages.journal(lsn);
        feed.sync();
        faults.reach(CrashPoint.CHECKPOINT_CATALOG);
        try {
            catalog.publish(new CatalogImage(stored, List.of(), pages.segments(), lsn, feed.size()));
        } catch (RuntimeException | Error failure) {
            pages.references().distrust();
            throw failure;
        }
        unapplied = lsn;
        transactions.checkpointed(stored, epoch);
        wal.append(new WalRecord.Checkpoint(0, stored.id(), lsn));
        wal.sync();
        wal.truncateBefore(lsn);
        return new Result(stored.id(), lsn, wal.segmentCount());
    }
}
