// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.maintenance;

import io.hstore.engine.catalog.CatalogImage;
import io.hstore.engine.catalog.CatalogStore;
import io.hstore.engine.catalog.Generation;
import io.hstore.engine.feed.ChangeFeed;
import io.hstore.engine.page.PageStore;
import io.hstore.engine.txn.CrashPoint;
import io.hstore.engine.txn.TransactionManager;
import io.hstore.engine.wal.WalRecord;
import io.hstore.engine.wal.WriteAheadLog;

import java.util.List;

public final class Checkpointer {

    public record Result(long generation, long lsn, int walSegments) {
    }

    private final TransactionManager transactions;
    private final PageStore pages;
    private final WriteAheadLog wal;
    private final ChangeFeed feed;
    private final CatalogStore catalog;
    private final ReferenceCounter references;
    private final CrashPoint.Injector faults;
    private final int historyLimit;
    private volatile long lastLsn;
    private volatile long completed;
    private long unapplied = -1;

    public Checkpointer(TransactionManager transactions, PageStore pages, WriteAheadLog wal, ChangeFeed feed,
                        CatalogStore catalog, ReferenceCounter references, CrashPoint.Injector faults, int historyLimit,
                        long recoveredLsn) {
        this.transactions = transactions;
        this.pages = pages;
        this.wal = wal;
        this.feed = feed;
        this.catalog = catalog;
        this.references = references;
        this.faults = faults;
        this.historyLimit = historyLimit;
        this.lastLsn = recoveredLsn;
    }

    public long walSinceCheckpoint() {
        return wal.end() - lastLsn;
    }

    public long completed() {
        return completed;
    }

    public Result checkpoint() {
        pages.checkpoint();
        Result result = transactions.exclusive(() -> {
            references.pause();
            try {
                return publish();
            } catch (RuntimeException | Error failure) {
                references.resume();
                throw failure;
            }
        });
        try {
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

    private Result publish() {
        if (unapplied >= 0) {
            pages.settleJournal(unapplied);
            unapplied = -1;
        }
        long lsn = wal.end();
        references.release(transactions.oldestReadable());
        pages.checkpoint();
        pages.journal(lsn);
        feed.sync();
        Generation current = transactions.current();
        List<Generation> history = transactions.history().stream()
                .filter(generation -> generation.id() != current.id())
                .sorted((a, b) -> Long.compare(b.id(), a.id()))
                .limit(historyLimit)
                .toList();
        faults.reach(CrashPoint.CHECKPOINT_CATALOG);
        try {
            catalog.publish(new CatalogImage(current, history, pages.segments(), lsn, feed.size()));
        } catch (RuntimeException | Error failure) {
            pages.references().distrust();
            throw failure;
        }
        unapplied = lsn;
        wal.append(new WalRecord.Checkpoint(0, current.id(), lsn));
        wal.sync();
        wal.truncateBefore(lsn);
        return new Result(current.id(), lsn, wal.segmentCount());
    }
}
