// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.bench;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

record JvmUsage(long collections, long pauseMillis, long allocated) {

    static JvmUsage now() {
        return now(true);
    }

    static JvmUsage onThisThread() {
        return now(false);
    }

    private static JvmUsage now(boolean allThreads) {
        long collections = 0;
        long pauseMillis = 0;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            collections += Math.max(0, collector.getCollectionCount());
            pauseMillis += Math.max(0, collector.getCollectionTime());
        }
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long allocated = allThreads ? threads.getTotalThreadAllocatedBytes() : threads.getCurrentThreadAllocatedBytes();
        return new JvmUsage(collections, pauseMillis, allocated);
    }

    JvmUsage since(JvmUsage start) {
        return new JvmUsage(collections - start.collections, pauseMillis - start.pauseMillis, allocated - start.allocated);
    }

    static long retainedHeap() {
        System.gc();
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
