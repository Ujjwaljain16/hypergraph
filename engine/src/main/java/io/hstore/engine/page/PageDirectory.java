// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import io.hstore.engine.HStoreException;

import java.nio.file.Path;

final class PageDirectory implements AutoCloseable {

    static final String FILE = "pages.dir";

    private static final int MAGIC = 0x52494450;
    private static final int FORMAT = 1;
    private static final int NEXT_OFFSET = 8;

    private final MappedLongs entries;
    private long next;

    private PageDirectory(MappedLongs entries) {
        this.entries = entries;
    }

    static PageDirectory open(Path path) {
        MappedLongs entries = MappedLongs.open(path, MAGIC, FORMAT, "page directory");
        if (entries.fresh()) {
            entries.header(NEXT_OFFSET, 1L);
        }
        PageDirectory directory = new PageDirectory(entries);
        directory.next = Math.max(1L, entries.header(NEXT_OFFSET));
        return directory;
    }

    long get(long index) {
        return entries.get(index);
    }

    void put(long index, long address) {
        entries.put(index, address);
    }

    long allocate() {
        long index = next;
        if (index > PageId.MAX_INDEX) {
            throw HStoreException.limit("page numbers exhausted");
        }
        reserve(index);
        return index;
    }

    void reserve(long index) {
        if (index >= next) {
            next = index + 1;
            entries.header(NEXT_OFFSET, next);
        }
    }

    long next() {
        return next;
    }

    void force() {
        entries.force();
    }

    @Override
    public void close() {
        entries.close();
    }
}
