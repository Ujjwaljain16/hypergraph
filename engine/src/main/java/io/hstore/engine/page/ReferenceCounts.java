package io.hstore.engine.page;

import java.nio.file.Path;

public final class ReferenceCounts implements AutoCloseable {

    static final String FILE = "pages.ref";

    private static final int MAGIC = 0x53464552;
    private static final int FORMAT = 1;
    private static final int STATE_OFFSET = 8;
    private static final long TRUSTED = 0;
    private static final long REBUILD = 1;
    private static final long DEAD = Long.MIN_VALUE;
    private static final long UNCHANGED = Long.MAX_VALUE;

    private final MappedLongs file;
    private final LongLongMap changed = new LongLongMap(1 << 12);
    private volatile boolean rebuilt;

    private ReferenceCounts(MappedLongs file) {
        this.file = file;
    }

    static ReferenceCounts open(Path path) {
        return new ReferenceCounts(MappedLongs.open(path, MAGIC, FORMAT, "reference counts"));
    }

    public static long count(long entry) {
        return Math.max(entry, 0);
    }

    public static boolean dead(long entry) {
        return entry < 0;
    }

    public static long deadAt(long entry) {
        return entry & Long.MAX_VALUE;
    }

    public static long deadSince(long generation) {
        return DEAD | generation;
    }

    public long entry(long index) {
        long overlay = changed.get(index, UNCHANGED);
        return overlay == UNCHANGED ? file.get(index) : overlay;
    }

    public void set(long index, long entry) {
        changed.put(index, entry);
    }

    public int changed() {
        return changed.size();
    }

    public boolean trusted() {
        return file.header(STATE_OFFSET) == TRUSTED;
    }

    public void distrust() {
        rebuilt = false;
        file.header(STATE_OFFSET, REBUILD);
        file.force();
    }

    public void rebuilt() {
        file.force();
        rebuilt = true;
    }

    void checkpointed() {
        if (rebuilt) {
            file.header(STATE_OFFSET, TRUSTED);
            file.force();
            rebuilt = false;
        }
    }

    void forEachChange(LongLongMap.Visitor visitor) {
        changed.forEach(visitor);
    }

    public void store(long index, long entry) {
        file.put(index, entry);
    }

    public void reset(long end) {
        changed.clear();
        for (long index = 1; index < end; index++) {
            if (file.get(index) != 0) {
                file.put(index, 0);
            }
        }
    }

    void discardChanges() {
        changed.clear();
    }

    void force() {
        file.force();
    }

    @Override
    public void close() {
        file.close();
    }
}
