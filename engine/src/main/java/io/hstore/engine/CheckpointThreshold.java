// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import java.util.OptionalLong;

public record CheckpointThreshold(long bytes, Source source) {

    public enum Source { AUTOMATIC, DATABASE, CONFIGURATION }

    public static final long FLOOR = 2L << 20;
    public static final long CEILING = 256L << 20;
    public static final int STORE_FRACTION = 4;

    public CheckpointThreshold {
        if (bytes < 1) {
            throw new IllegalArgumentException("a checkpoint threshold must be at least one byte: " + bytes);
        }
    }

    public static long automatic(long liveBytes) {
        return Math.clamp(liveBytes / STORE_FRACTION, FLOOR, CEILING);
    }

    public static CheckpointThreshold resolve(OptionalLong stored, long configured, long liveBytes) {
        if (stored.isPresent()) {
            return new CheckpointThreshold(stored.getAsLong(), Source.DATABASE);
        }
        if (configured != EngineOptions.AUTOMATIC_CHECKPOINTS) {
            return new CheckpointThreshold(configured, Source.CONFIGURATION);
        }
        return new CheckpointThreshold(automatic(liveBytes), Source.AUTOMATIC);
    }
}
