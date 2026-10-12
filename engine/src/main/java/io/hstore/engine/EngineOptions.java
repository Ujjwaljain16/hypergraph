// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.catalog.Slot;
import io.hstore.engine.txn.CrashPoint;
import io.hstore.engine.txn.Derivation;
import io.hstore.engine.txn.Durability;
import io.hstore.engine.txn.WalMode;

import java.time.Duration;
import java.util.List;

public record EngineOptions(
        int pageSize,
        int pagesPerSegment,
        long walSegmentBytes,
        long nodeCacheBytes,
        Durability durability,
        WalMode walMode,
        int historyLimit,
        long checkpointWalBytes,
        long writeBufferBytes,
        double compactionLiveRatio,
        long feedRetention,
        Duration maintenanceInterval,
        List<Slot<?>> slots,
        List<Derivation> derivations,
        CrashPoint.Injector faults) {

    public EngineOptions {
        if (Integer.bitCount(pageSize) != 1 || pageSize < 1024 || pageSize > 1 << 20) {
            throw new IllegalArgumentException("page size must be a power of two between 1 KiB and 1 MiB: " + pageSize);
        }
        if (compactionLiveRatio <= 0 || compactionLiveRatio >= 1) {
            throw new IllegalArgumentException("compaction live ratio must be between 0 and 1: " + compactionLiveRatio);
        }
        if (nodeCacheBytes < 1) {
            throw new IllegalArgumentException("node cache must be at least one byte: " + nodeCacheBytes);
        }
        if (writeBufferBytes < 1) {
            throw new IllegalArgumentException("the write buffer must be at least one byte: " + writeBufferBytes);
        }
        if (maintenanceInterval.isNegative() || maintenanceInterval.isZero()) {
            throw new IllegalArgumentException("the maintenance interval must be positive: " + maintenanceInterval);
        }
        if (feedRetention < 1) {
            throw new IllegalArgumentException("feed retention must keep at least one generation: " + feedRetention);
        }
        slots = List.copyOf(slots);
        derivations = List.copyOf(derivations);
    }

    public static EngineOptions defaults() {
        return new EngineOptions(16 * 1024, 16 * 1024, 64L << 20, 256L << 20, Durability.SYNC, WalMode.PAGE_REFERENCES, 64,
                256L << 20, 64L << 20, 0.5, 100_000, Duration.ofMillis(500), List.of(), List.of(), CrashPoint.Injector.NONE);
    }

    public EngineOptions withPageSize(int bytes) {
        return new EngineOptions(bytes, pagesPerSegment, walSegmentBytes, nodeCacheBytes, durability, walMode, historyLimit,
                checkpointWalBytes, writeBufferBytes, compactionLiveRatio, feedRetention, maintenanceInterval, slots, derivations, faults);
    }

    public EngineOptions withWalSegmentBytes(long bytes) {
        return new EngineOptions(pageSize, pagesPerSegment, bytes, nodeCacheBytes, durability, walMode, historyLimit,
                checkpointWalBytes, writeBufferBytes, compactionLiveRatio, feedRetention, maintenanceInterval, slots, derivations, faults);
    }

    public EngineOptions withPagesPerSegment(int pages) {
        return new EngineOptions(pageSize, pages, walSegmentBytes, nodeCacheBytes, durability, walMode, historyLimit,
                checkpointWalBytes, writeBufferBytes, compactionLiveRatio, feedRetention, maintenanceInterval, slots, derivations, faults);
    }

    public EngineOptions withDurability(Durability mode) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, nodeCacheBytes, mode, walMode, historyLimit,
                checkpointWalBytes, writeBufferBytes, compactionLiveRatio, feedRetention, maintenanceInterval, slots, derivations, faults);
    }

    public EngineOptions withWalMode(WalMode mode) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, nodeCacheBytes, durability, mode, historyLimit,
                checkpointWalBytes, writeBufferBytes, compactionLiveRatio, feedRetention, maintenanceInterval, slots, derivations, faults);
    }

    public EngineOptions withHistoryLimit(int generations) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, nodeCacheBytes, durability, walMode, generations,
                checkpointWalBytes, writeBufferBytes, compactionLiveRatio, feedRetention, maintenanceInterval, slots, derivations, faults);
    }

    public EngineOptions withCheckpointWalBytes(long bytes) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, nodeCacheBytes, durability, walMode, historyLimit,
                bytes, writeBufferBytes, compactionLiveRatio, feedRetention, maintenanceInterval, slots, derivations, faults);
    }

    public EngineOptions withWriteBufferBytes(long bytes) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, nodeCacheBytes, durability, walMode, historyLimit,
                checkpointWalBytes, bytes, compactionLiveRatio, feedRetention, maintenanceInterval, slots, derivations, faults);
    }

    public EngineOptions withMaintenanceInterval(Duration interval) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, nodeCacheBytes, durability, walMode, historyLimit,
                checkpointWalBytes, writeBufferBytes, compactionLiveRatio, feedRetention, interval, slots, derivations, faults);
    }

    public EngineOptions withNodeCacheBytes(long bytes) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, bytes, durability, walMode, historyLimit,
                checkpointWalBytes, writeBufferBytes, compactionLiveRatio, feedRetention, maintenanceInterval, slots, derivations, faults);
    }

    public EngineOptions withCompactionLiveRatio(double ratio) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, nodeCacheBytes, durability, walMode, historyLimit,
                checkpointWalBytes, writeBufferBytes, ratio, feedRetention, maintenanceInterval, slots, derivations, faults);
    }

    public EngineOptions withFeedRetention(long generations) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, nodeCacheBytes, durability, walMode, historyLimit,
                checkpointWalBytes, writeBufferBytes, compactionLiveRatio, generations, maintenanceInterval, slots, derivations, faults);
    }

    public EngineOptions withExtensions(List<Slot<?>> extensionSlots, List<Derivation> extensionDerivations) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, nodeCacheBytes, durability, walMode, historyLimit,
                checkpointWalBytes, writeBufferBytes, compactionLiveRatio, feedRetention, maintenanceInterval, extensionSlots, extensionDerivations, faults);
    }

    public EngineOptions withFaults(CrashPoint.Injector injector) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, nodeCacheBytes, durability, walMode, historyLimit,
                checkpointWalBytes, writeBufferBytes, compactionLiveRatio, feedRetention, maintenanceInterval, slots, derivations, injector);
    }
}
