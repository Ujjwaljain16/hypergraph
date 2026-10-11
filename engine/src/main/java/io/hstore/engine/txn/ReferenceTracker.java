// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.txn;

import io.hstore.engine.catalog.Generation;

public interface ReferenceTracker {

    ReferenceTracker NONE = new ReferenceTracker() {
        @Override
        public void committed(Generation before, Generation after, long[] spilled) {
        }

        @Override
        public void discarded(long[] spilled) {
        }
    };

    void committed(Generation before, Generation after, long[] spilled);

    void discarded(long[] spilled);
}
