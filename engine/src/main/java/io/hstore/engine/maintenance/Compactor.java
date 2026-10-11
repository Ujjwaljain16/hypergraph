package io.hstore.engine.maintenance;

import io.hstore.engine.page.PageStore;
import io.hstore.engine.page.SegmentInfo;
import io.hstore.engine.page.SegmentState;
import io.hstore.engine.txn.CrashPoint;
import io.hstore.engine.txn.TransactionManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

public final class Compactor {

    public record Report(Map<Integer, Long> liveBytes, List<Integer> compacted, long generation) {
    }

    private final TransactionManager transactions;
    private final PageStore pages;
    private final Checkpointer checkpointer;
    private final CrashPoint.Injector faults;
    private final Map<Integer, Long> retiredBeforeCheckpoint = new ConcurrentHashMap<>();

    public Compactor(TransactionManager transactions, PageStore pages, Checkpointer checkpointer, CrashPoint.Injector faults) {
        this.transactions = transactions;
        this.pages = pages;
        this.checkpointer = checkpointer;
        this.faults = faults;
    }

    public boolean worthwhile(double liveThreshold) {
        return !victims(pages.liveBytes(), liveThreshold, 1).isEmpty();
    }

    private List<Integer> victims(Map<Integer, Long> liveBytes, double liveThreshold, int maxVictims) {
        int active = pages.activeSegment();
        return pages.segments().stream()
                .filter(segment -> segment.state() == SegmentState.SEALED && segment.id() != active && pages.settled(segment.id()))
                .filter(segment -> liveBytes.getOrDefault(segment.id(), 0L) < segment.bytes() * liveThreshold)
                .sorted(Comparator.comparingDouble(segment -> (double) liveBytes.getOrDefault(segment.id(), 0L) / Math.max(1, segment.bytes())))
                .limit(maxVictims)
                .map(SegmentInfo::id)
                .toList();
    }

    public Report compact(double liveThreshold) {
        return compact(liveThreshold, Integer.MAX_VALUE);
    }

    public Report compact(double liveThreshold, int maxVictims) {
        Map<Integer, Long> liveBytes = pages.liveBytes();
        List<Integer> victims = victims(liveBytes, liveThreshold, maxVictims);
        List<Integer> emptied = new ArrayList<>();
        RuntimeException failure = null;
        for (int victim : victims) {
            pages.transition(victim, SegmentState.COMPACTING, 0);
            try {
                pages.evacuate(victim);
                emptied.add(victim);
            } catch (RuntimeException e) {
                pages.transition(victim, SegmentState.SEALED, 0);
                failure = e;
                break;
            }
        }
        if (!emptied.isEmpty()) {
            faults.reach(CrashPoint.COMPACTION_MOVE);
        }
        long retiredAt = transactions.current().id();
        long checkpoints = checkpointer.completed();
        emptied.forEach(id -> {
            pages.transition(id, SegmentState.RETIRED, retiredAt);
            retiredBeforeCheckpoint.put(id, checkpoints);
        });
        if (!emptied.isEmpty()) {
            faults.reach(CrashPoint.COMPACTION_RETIRE);
        }
        if (failure != null) {
            throw failure;
        }
        return new Report(liveBytes, emptied, retiredAt);
    }

    public List<Integer> reclaim() {
        OptionalLong pinned = transactions.oldestPinned();
        long checkpoints = checkpointer.completed();
        List<Integer> reclaimable = pages.segments().stream()
                .filter(segment -> segment.state() == SegmentState.RETIRED)
                .filter(segment -> pinned.isEmpty() || pinned.getAsLong() > segment.retiredAt())
                .filter(segment -> checkpoints > retiredBeforeCheckpoint.getOrDefault(segment.id(), -1L))
                .map(SegmentInfo::id)
                .toList();
        reclaimable.forEach(id -> {
            pages.delete(id);
            retiredBeforeCheckpoint.remove(id);
        });
        return reclaimable;
    }
}
