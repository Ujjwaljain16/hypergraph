// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.txn;


public interface ReferenceTracker {

    ReferenceTracker NONE = new ReferenceTracker() {
        @Override
        public void provisional(long generation, long[] pages) {
        }

        @Override
        public void discarded(long[] spilled) {
        }
    };

    void provisional(long generation, long[] pages);

    void discarded(long[] spilled);
}
