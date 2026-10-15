// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.security;

import io.hstore.engine.HStoreException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class SignIns {

    private record Window(int attempts, long resetAt) {
    }

    private record Key(String name, int limit) {
    }

    public static final int USER_LIMIT = 10;
    public static final int ORIGIN_LIMIT = 20;
    public static final Duration WINDOW = Duration.ofMinutes(15);

    private static final int TRACKED_KEYS = 16_384;

    private final int userLimit;
    private final int originLimit;
    private final long windowNanos;
    private final LongSupplier clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public SignIns() {
        this(USER_LIMIT, ORIGIN_LIMIT, WINDOW, System::nanoTime);
    }

    public SignIns(int userLimit, int originLimit, Duration window, LongSupplier clock) {
        this.userLimit = userLimit;
        this.originLimit = originLimit;
        this.windowNanos = window.toNanos();
        this.clock = clock;
    }

    public <T> Optional<T> attempt(String user, Optional<String> origin, Supplier<Optional<T>> check) {
        Key account = new Key("user:" + user, userLimit);
        List<Key> keys = Stream.concat(Stream.of(account), origin.map(address -> new Key("origin:" + address, originLimit)).stream()).toList();
        long now = clock.getAsLong();
        if (windows.size() >= TRACKED_KEYS) {
            windows.values().removeIf(window -> now >= window.resetAt());
        }
        for (int reserved = 0; reserved < keys.size(); reserved++) {
            long wait = reserve(keys.get(reserved), now);
            if (wait > 0) {
                keys.subList(0, reserved).forEach(this::release);
                throw HStoreException.limit("too many failed sign-in attempts; try again in " + wait + " seconds");
            }
        }
        Optional<T> result = check.get();
        if (result.isPresent()) {
            windows.remove(account.name());
            keys.stream().filter(key -> key != account).forEach(this::release);
        }
        return result;
    }

    private long reserve(Key key, long now) {
        long[] wait = {0};
        windows.compute(key.name(), (_, window) -> {
            if (window == null || now >= window.resetAt()) {
                return new Window(1, now + windowNanos);
            }
            if (window.attempts() >= key.limit()) {
                wait[0] = Math.max(1, Duration.ofNanos(window.resetAt() - now).toSeconds());
                return window;
            }
            return new Window(window.attempts() + 1, window.resetAt());
        });
        return wait[0];
    }

    private void release(Key key) {
        windows.computeIfPresent(key.name(), (_, window) -> window.attempts() <= 1 ? null : new Window(window.attempts() - 1, window.resetAt()));
    }
}
