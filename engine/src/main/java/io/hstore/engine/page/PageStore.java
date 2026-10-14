// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import io.hstore.engine.HStoreException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

public final class PageStore implements AutoCloseable {

    public record Scan(long[] dead, long[] unreferenced, long unplaced) {
    }

    private static final long PASS_BYTES = 4L << 20;
    private static final long FIRST_SEGMENT_BYTES = 8L << 20;
    private static final long GROWTH_FRACTION = 8;

    private final Path directory;
    private final int pageSize;
    private final int firstUnits;
    private final int maximumUnits;
    private final FilePool pool;
    private final Map<Integer, SegmentFile> files = new ConcurrentHashMap<>();
    private final ConcurrentSkipListMap<Integer, SegmentInfo> segments = new ConcurrentSkipListMap<>();
    private final Map<Integer, AtomicLong> live = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicInteger> unwritten = new ConcurrentHashMap<>();
    private final ReentrantLock allocation = new ReentrantLock();
    private final AtomicLong pagesWritten = new AtomicLong();
    private final AtomicLong bytesWritten = new AtomicLong();
    private final AtomicLong pagesRead = new AtomicLong();
    private final AtomicLong bytesRead = new AtomicLong();
    private PageDirectory addresses;
    private ReferenceCounts references;
    private long[] released = new long[64];
    private int releasedCount;
    private int activeSegment;
    private int nextUnit;
    private int activeUnits;

    private PageStore(Path directory, int pageSize, long firstSegmentBytes, int pagesPerSegment, int openFiles) {
        this.directory = directory;
        this.pageSize = pageSize;
        this.maximumUnits = (int) Math.min(PageAddress.MAX_OFFSET + 1L, (long) pagesPerSegment * pageSize / PageId.UNIT_BYTES);
        this.firstUnits = (int) Math.min(firstSegmentBytes / PageId.UNIT_BYTES, maximumUnits);
        this.pool = new FilePool(openFiles);
    }

    public static PageStore open(Path directory, int pageSize, int pagesPerSegment, int openFiles, Collection<SegmentInfo> known) {
        return open(directory, pageSize, pagesPerSegment, openFiles, known, -1);
    }

    public static PageStore open(Path directory, int pageSize, int pagesPerSegment, int openFiles, Collection<SegmentInfo> known,
                                 long checkpointLsn) {
        return open(directory, pageSize, FIRST_SEGMENT_BYTES, pagesPerSegment, openFiles, known, checkpointLsn);
    }

    static PageStore open(Path directory, int pageSize, long firstSegmentBytes, int pagesPerSegment, int openFiles,
                          Collection<SegmentInfo> known, long checkpointLsn) {
        PageStore store = new PageStore(directory, pageSize, firstSegmentBytes, pagesPerSegment, openFiles);
        store.restore(known, checkpointLsn);
        return store;
    }

    private void restore(Collection<SegmentInfo> known, long checkpointLsn) {
        try {
            Files.createDirectories(directory);
            addresses = PageDirectory.open(directory.resolve(PageDirectory.FILE));
            references = ReferenceCounts.open(directory.resolve(ReferenceCounts.FILE));
            Path journal = directory.resolve(ReferenceJournal.FILE);
            ReferenceJournal.read(journal, checkpointLsn).ifPresent(this::replay);
            ReferenceJournal.clear(journal);
            if (references.fresh() && addresses.next() > 1) {
                references.distrust();
            }
            Map<Integer, SegmentInfo> byId = new ConcurrentHashMap<>();
            known.forEach(info -> byId.put(info.id(), info));
            try (Stream<Path> listing = Files.list(directory)) {
                for (Path file : listing.filter(p -> p.getFileName().toString().endsWith(".seg")).toList()) {
                    int id = SegmentFile.idOf(file);
                    SegmentFile segment = SegmentFile.open(id, file, pool);
                    files.put(id, segment);
                    long units = segment.unitsOnDisk();
                    SegmentInfo info = byId.getOrDefault(id, new SegmentInfo(id, SegmentState.SEALED, units, units, 0));
                    if (info.state() == SegmentState.COMPACTING) {
                        info = info.withState(SegmentState.SEALED, 0);
                    }
                    segments.put(id, info.withAllocation(info.pages(), Math.max(info.units(), units)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open page store " + directory, e);
        }
        segments.descendingMap().values().stream()
                .filter(info -> info.state() == SegmentState.ACTIVE)
                .findFirst()
                .ifPresent(info -> {
                    activeSegment = info.id();
                    nextUnit = Math.toIntExact(info.units());
                    activeUnits = unitsForNextSegment(info.id());
                });
        files.forEach((id, file) -> {
            if (id != activeSegment) {
                file.seal();
            }
        });
    }

    private void replay(ReferenceJournal.Contents journal) {
        long[] counts = journal.counts();
        for (int i = 0; i < counts.length; i += 2) {
            references.store(counts[i], counts[i + 1]);
        }
        for (long index : journal.cleared()) {
            addresses.put(index, PageAddress.NONE);
        }
        references.force();
        addresses.force();
    }

    public int pageSize() {
        return pageSize;
    }

    public ReferenceCounts references() {
        return references;
    }

    public long pageNumbers() {
        allocation.lock();
        try {
            return addresses.next();
        } finally {
            allocation.unlock();
        }
    }

    public long allocate(int length) {
        if (length > pageSize) {
            throw new IllegalArgumentException("page image exceeds page size: " + length);
        }
        int units = PageId.unitsFor(length);
        allocation.lock();
        try {
            long index = addresses.allocate();
            long address = claim(units);
            addresses.put(index, address);
            adjustLive(address, 1);
            unwritten.computeIfAbsent(PageAddress.segmentOf(address), _ -> new AtomicInteger()).incrementAndGet();
            return PageId.pack(index, 0);
        } finally {
            allocation.unlock();
        }
    }

    public boolean settled(int segment) {
        AtomicInteger pending = unwritten.get(segment);
        return pending == null || pending.get() == 0;
    }

    public long evacuate(int segment) {
        SegmentFile file = files.get(segment);
        MemorySegment view = file == null ? MemorySegment.NULL : file.view();
        long units = view.byteSize() / PageId.UNIT_BYTES;
        long[] moved = new long[96];
        int count = 0;
        long pass = 0;
        long total = 0;
        for (long unit = 0; unit < units; ) {
            long position = unit * PageId.UNIT_BYTES;
            long address = PageHeader.startsAt(view, position) ? addresses.get(PageId.indexOf(PageHeader.pageIdAt(view, position))) : PageAddress.NONE;
            if (address == PageAddress.NONE || PageAddress.segmentOf(address) != segment || PageAddress.offsetOf(address) != unit) {
                unit++;
                continue;
            }
            long pageId = PageHeader.pageIdAt(view, position);
            int pageUnits = PageAddress.unitsOf(address);
            MemorySegment image = view.asSlice(position, Math.min((long) pageUnits * PageId.UNIT_BYTES, view.byteSize() - position));
            MemorySegment bytes = image.asSlice(0, PageHeader.SIZE + PageHeader.verify(image, pageId).payloadLength());
            long to;
            allocation.lock();
            try {
                to = claim(pageUnits);
            } finally {
                allocation.unlock();
            }
            files.get(PageAddress.segmentOf(to)).write((long) PageAddress.offsetOf(to) * PageId.UNIT_BYTES, bytes);
            pagesWritten.incrementAndGet();
            bytesWritten.addAndGet(bytes.byteSize());
            if (count + 3 > moved.length) {
                moved = Arrays.copyOf(moved, moved.length * 2);
            }
            moved[count++] = pageId;
            moved[count++] = address;
            moved[count++] = to;
            pass += bytes.byteSize();
            total += bytes.byteSize();
            if (pass >= PASS_BYTES) {
                repoint(moved, count);
                count = 0;
                pass = 0;
            }
            unit += pageUnits;
        }
        repoint(moved, count);
        AtomicLong left = live.get(segment);
        if (left != null && left.get() > 0) {
            throw HStoreException.corrupt(PageId.NONE, "segment " + segment + " still holds " + left.get()
                    + " bytes of pages that could not be read and moved; it is kept");
        }
        return total;
    }

    private void repoint(long[] moved, int count) {
        if (count == 0) {
            return;
        }
        sync();
        allocation.lock();
        try {
            for (int i = 0; i < count; i += 3) {
                long index = PageId.indexOf(moved[i]);
                if (addresses.get(index) == moved[i + 1]) {
                    addresses.put(index, moved[i + 2]);
                    adjustLive(moved[i + 1], -1);
                    adjustLive(moved[i + 2], 1);
                }
            }
        } finally {
            allocation.unlock();
        }
    }

    private long claim(int units) {
        if (activeSegment == 0 || nextUnit + units > activeUnits) {
            rollSegment();
        }
        int offset = nextUnit;
        nextUnit += units;
        segments.computeIfPresent(activeSegment, (_, info) -> info.withAllocation(info.pages() + 1, Math.max(info.units(), offset + (long) units)));
        return new PageAddress(activeSegment, offset, units).pack();
    }

    private void rollSegment() {
        if (activeSegment != 0) {
            segments.computeIfPresent(activeSegment, (_, info) -> info.withState(SegmentState.SEALED, 0));
            SegmentFile previous = files.get(activeSegment);
            if (previous != null) {
                previous.seal();
            }
        }
        int id = segments.isEmpty() ? 1 : segments.lastKey() + 1;
        activeUnits = unitsForNextSegment(id);
        files.put(id, SegmentFile.create(id, SegmentFile.pathFor(directory, id), pool));
        segments.put(id, new SegmentInfo(id, SegmentState.ACTIVE, 0, 0, 0));
        activeSegment = id;
        nextUnit = 0;
    }

    private int unitsForNextSegment(int id) {
        long stored = segments.values().stream()
                .filter(info -> info.id() != id && info.state() != SegmentState.RETIRED)
                .mapToLong(SegmentInfo::units)
                .sum();
        long units = firstUnits;
        while (units * 2 <= maximumUnits && units * 2 * GROWTH_FRACTION <= stored) {
            units *= 2;
        }
        return (int) units;
    }

    private void adjustLive(long address, int sign) {
        live.computeIfAbsent(PageAddress.segmentOf(address), _ -> new AtomicLong())
                .addAndGet(sign * (long) PageAddress.unitsOf(address) * PageId.UNIT_BYTES);
    }

    public void write(long pageId, MemorySegment image) {
        writeUnsettled(pageId, image);
        settle(pageId);
    }

    public void settle(long pageId) {
        AtomicInteger pending = unwritten.get(PageAddress.segmentOf(locate(pageId)));
        if (pending != null) {
            pending.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    public void writeUnsettled(long pageId, MemorySegment image) {
        if (image.byteSize() > pageSize) {
            throw new IllegalArgumentException("page image exceeds page size: " + image.byteSize());
        }
        long address = locate(pageId);
        if (PageId.unitsFor(image.byteSize()) > PageAddress.unitsOf(address)) {
            throw new IllegalArgumentException("page image exceeds its allocation of " + PageAddress.unitsOf(address) + " units");
        }
        int segment = PageAddress.segmentOf(address);
        int offset = PageAddress.offsetOf(address);
        SegmentFile file = files.computeIfAbsent(segment, id -> {
            segments.putIfAbsent(id, new SegmentInfo(id, SegmentState.SEALED, 0, 0, 0));
            return SegmentFile.open(id, SegmentFile.pathFor(directory, id), pool);
        });
        file.write((long) offset * PageId.UNIT_BYTES, image);
        long end = offset + (long) PageId.unitsFor(image.byteSize());
        segments.computeIfPresent(segment, (_, info) -> info.units() >= end ? info : info.withAllocation(info.pages() + 1, end));
        pagesWritten.incrementAndGet();
        bytesWritten.addAndGet(image.byteSize());
    }

    long mappedBytes(int segment) {
        SegmentFile file = files.get(segment);
        return file == null ? 0 : file.mappedBytes();
    }

    public MemorySegment read(long pageId) {
        long address = locate(pageId);
        SegmentFile file = files.get(PageAddress.segmentOf(address));
        if (file == null) {
            throw HStoreException.corrupt(pageId, "segment does not exist");
        }
        pagesRead.incrementAndGet();
        MemorySegment page = file.read((long) PageAddress.offsetOf(address) * PageId.UNIT_BYTES, PageAddress.unitsOf(address) * PageId.UNIT_BYTES);
        bytesRead.addAndGet(page.byteSize());
        return page;
    }

    public long addressOf(long pageId) {
        return locate(pageId);
    }

    public long addressOrNone(long pageId) {
        return addresses.get(PageId.indexOf(pageId));
    }

    public int segmentOf(long pageId) {
        return PageAddress.segmentOf(locate(pageId));
    }

    public void place(long pageId, long address) {
        allocation.lock();
        try {
            long index = PageId.indexOf(pageId);
            long previous = addresses.get(index);
            if (previous != PageAddress.NONE) {
                adjustLive(previous, -1);
            }
            addresses.put(index, address);
            adjustLive(address, 1);
            addresses.reserve(index);
            if (PageAddress.segmentOf(address) == activeSegment) {
                nextUnit = Math.max(nextUnit, PageAddress.offsetOf(address) + PageAddress.unitsOf(address));
            }
        } finally {
            allocation.unlock();
        }
    }

    public void release(long pageId) {
        long index = PageId.indexOf(pageId);
        long address = addresses.get(index);
        if (address == PageAddress.NONE) {
            return;
        }
        adjustLive(address, -1);
        if (releasedCount == released.length) {
            released = Arrays.copyOf(released, released.length * 2);
        }
        released[releasedCount++] = index;
    }

    public Scan scan() {
        long[] dead = new long[64];
        int deadCount = 0;
        long[] unreferenced = new long[64];
        int unreferencedCount = 0;
        long unplaced = 0;
        allocation.lock();
        try {
            live.clear();
            long end = addresses.next();
            for (long index = 1; index < end; index++) {
                long address = addresses.get(index);
                long entry = references.entry(index);
                if (address == PageAddress.NONE) {
                    unplaced += entry > 0 ? 1 : 0;
                } else {
                    adjustLive(address, 1);
                    if (entry == 0) {
                        if (unreferencedCount == unreferenced.length) {
                            unreferenced = Arrays.copyOf(unreferenced, unreferenced.length * 2);
                        }
                        unreferenced[unreferencedCount++] = PageId.pack(index, 0);
                    }
                }
                if (ReferenceCounts.dead(entry)) {
                    if (deadCount + 2 > dead.length) {
                        dead = Arrays.copyOf(dead, dead.length * 2);
                    }
                    dead[deadCount++] = ReferenceCounts.deadAt(entry);
                    dead[deadCount++] = index;
                }
            }
        } finally {
            allocation.unlock();
        }
        return new Scan(Arrays.copyOf(dead, deadCount), Arrays.copyOf(unreferenced, unreferencedCount), unplaced);
    }

    private long locate(long pageId) {
        long address = addresses.get(PageId.indexOf(pageId));
        if (address == PageAddress.NONE) {
            throw HStoreException.corrupt(pageId, "page has no address");
        }
        return address;
    }

    public Map<Integer, Long> liveBytes() {
        Map<Integer, Long> bytes = new TreeMap<>();
        live.forEach((segment, total) -> {
            if (total.get() > 0) {
                bytes.put(segment, total.get());
            }
        });
        return bytes;
    }

    public void sync() {
        pool.sync();
    }

    int openFiles() {
        return pool.open();
    }

    int unsyncedFiles() {
        return pool.unsynced();
    }

    long segmentForces() {
        return pool.forces();
    }

    public void checkpoint() {
        sync();
        addresses.force();
    }

    public void journal(long lsn) {
        ReferenceJournal.write(directory.resolve(ReferenceJournal.FILE), lsn, references, released, releasedCount);
    }

    public void applyJournal() {
        references.forEachChange(references::store);
        for (int i = 0; i < releasedCount; i++) {
            addresses.put(released[i], PageAddress.NONE);
        }
        references.force();
        addresses.force();
        ReferenceJournal.clear(directory.resolve(ReferenceJournal.FILE));
        references.discardChanges();
        releasedCount = 0;
        references.checkpointed();
    }

    public void settleJournal(long lsn) {
        Path journal = directory.resolve(ReferenceJournal.FILE);
        ReferenceJournal.read(journal, lsn).ifPresent(this::replay);
        ReferenceJournal.clear(journal);
        references.checkpointed();
    }

    public List<SegmentInfo> segments() {
        return List.copyOf(segments.values());
    }

    public long segmentBytes() {
        allocation.lock();
        try {
            return (long) activeUnits * PageId.UNIT_BYTES;
        } finally {
            allocation.unlock();
        }
    }

    public void sealActive(int segment) {
        allocation.lock();
        try {
            if (activeSegment == segment && segment != 0) {
                segments.computeIfPresent(activeSegment, (_, info) -> info.withState(SegmentState.SEALED, 0));
                SegmentFile file = files.get(activeSegment);
                if (file != null) {
                    file.seal();
                }
                activeSegment = 0;
            }
        } finally {
            allocation.unlock();
        }
    }

    public int activeSegment() {
        allocation.lock();
        try {
            return activeSegment;
        } finally {
            allocation.unlock();
        }
    }

    public void transition(int segment, SegmentState next, long at) {
        segments.computeIfPresent(segment, (_, info) -> info.withState(next, at));
    }

    public void delete(int segment) {
        SegmentFile file = files.remove(segment);
        if (file != null) {
            file.delete();
        }
        segments.remove(segment);
        live.remove(segment);
        unwritten.remove(segment);
    }

    public long pagesWritten() {
        return pagesWritten.get();
    }

    public long bytesWritten() {
        return bytesWritten.get();
    }

    public long pagesRead() {
        return pagesRead.get();
    }

    public long bytesRead() {
        return bytesRead.get();
    }

    @Override
    public void close() {
        files.values().forEach(SegmentFile::close);
        files.clear();
        addresses.close();
        references.close();
    }
}
