// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

public final class Epoch {

    private volatile boolean published;

    public void publish() {
        published = true;
    }

    public boolean published() {
        return published;
    }
}
