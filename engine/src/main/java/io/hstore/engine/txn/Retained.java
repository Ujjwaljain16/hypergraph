// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.txn;

import io.hstore.engine.catalog.Generation;

import java.util.concurrent.atomic.AtomicInteger;

final class Retained {

    private final AtomicInteger readers = new AtomicInteger();
    private volatile Generation generation;
    private volatile boolean dropped;

    Retained(Generation generation) {
        this.generation = generation;
    }

    Generation generation() {
        return generation;
    }

    void rebind(Generation rebound) {
        generation = rebound;
    }

    boolean pin() {
        readers.incrementAndGet();
        if (!dropped) {
            return true;
        }
        readers.decrementAndGet();
        return false;
    }

    void unpin() {
        readers.decrementAndGet();
    }

    boolean pinned() {
        return readers.get() > 0;
    }

    boolean drop() {
        if (pinned()) {
            return false;
        }
        dropped = true;
        if (pinned()) {
            dropped = false;
            return false;
        }
        return true;
    }
}
