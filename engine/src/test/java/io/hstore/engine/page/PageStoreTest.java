// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import io.hstore.engine.HStoreException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageStoreTest {

    private static final int PAGE_SIZE = 4096;
    private static final int PAGES_PER_SEGMENT = 2048;
    private static final int PAYLOAD_LENGTH_OFFSET = 24;

    @TempDir
    Path directory;

    @Test
    void pagesReadBackExactlyAsWrittenAcrossSegmentsAndReopens() {
        Random random = new Random(7);
        Map<Long, byte[]> written = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>();
        List<SegmentInfo> segments;
        try (PageStore store = PageStore.open(directory, PAGE_SIZE, PAGES_PER_SEGMENT, List.of())) {
            for (int i = 0; i < 16_000; i++) {
                byte[] image = image(random, PageHeader.SIZE + random.nextInt(PAGE_SIZE - PageHeader.SIZE + 1));
                long pageId = store.allocate(image.length);
                store.write(pageId, MemorySegment.ofArray(image));
                written.put(pageId, image);
                ids.add(pageId);
                for (int probe = 0; probe < 3; probe++) {
                    long earlier = ids.get(random.nextInt(ids.size()));
                    assertArrayEquals(written.get(earlier), bytes(store.read(earlier)), "page " + PageId.unpack(earlier));
                }
            }
            assertTrue(store.segments().size() > 3, "the test should span several segments");
            written.forEach((pageId, image) -> assertArrayEquals(image, bytes(store.read(pageId))));
            segments = store.segments();
        }
        try (PageStore reopened = PageStore.open(directory, PAGE_SIZE, PAGES_PER_SEGMENT, segments)) {
            written.forEach((pageId, image) -> assertArrayEquals(image, bytes(reopened.read(pageId))));
            long first = ids.getFirst();
            reopened.read(first);
            assertTrue(reopened.mappedBytes(reopened.segmentOf(first)) > 0);
        }
    }

    @Test
    void pagesCannotBeChangedThroughWhatReadReturns() {
        try (PageStore store = PageStore.open(directory, PAGE_SIZE, PAGES_PER_SEGMENT, List.of())) {
            long first = write(store, PAGE_SIZE);
            fillPastOneSegment(store);
            long last = write(store, 200);
            for (long pageId : new long[]{first, last}) {
                MemorySegment page = store.read(pageId);
                assertThrows(IllegalArgumentException.class, () -> page.set(ValueLayout.JAVA_BYTE, 0, (byte) 1));
            }
        }
    }

    @Test
    void finishedSegmentsAreMappedAndTheGrowingOneIsNotUntilItGrows() {
        try (PageStore store = PageStore.open(directory, PAGE_SIZE, PAGES_PER_SEGMENT, List.of())) {
            long finished = write(store, PAGE_SIZE);
            fillPastOneSegment(store);
            long growing = write(store, PAGE_SIZE);
            store.read(finished);
            store.read(growing);
            assertTrue(store.mappedBytes(store.segmentOf(finished)) >= (long) PAGES_PER_SEGMENT * PAGE_SIZE - PAGE_SIZE);
            assertEquals(0, store.mappedBytes(store.segmentOf(growing)));
            for (int i = 0; i < 1100; i++) {
                write(store, PAGE_SIZE);
            }
            long later = write(store, PAGE_SIZE);
            store.read(later);
            assertTrue(store.mappedBytes(store.segmentOf(later)) > 4L << 20);
        }
    }

    @Test
    void pagesAreNumberedInOrderWhicheverSegmentHoldsThem() {
        try (PageStore store = PageStore.open(directory, PAGE_SIZE, PAGES_PER_SEGMENT, List.of())) {
            List<Long> ids = new ArrayList<>();
            while (store.segments().size() < 3) {
                ids.add(write(store, PAGE_SIZE / 2));
            }
            for (int i = 0; i < ids.size(); i++) {
                assertEquals(i + 1, PageId.indexOf(ids.get(i)));
            }
            assertNotEquals(store.segmentOf(ids.getFirst()), store.segmentOf(ids.getLast()));
        }
    }

    @Test
    void aNumberWithoutAnAddressIsReportedAsCorrupt() {
        try (PageStore store = PageStore.open(directory, PAGE_SIZE, PAGES_PER_SEGMENT, List.of())) {
            write(store, 200);
            assertThrows(HStoreException.CorruptPage.class, () -> store.read(PageId.pack(99, 0)));
        }
    }

    @Test
    void aPageCannotBeWrittenPastWhatWasAllocatedForIt() {
        try (PageStore store = PageStore.open(directory, PAGE_SIZE, PAGES_PER_SEGMENT, List.of())) {
            long pageId = store.allocate(100);
            assertThrows(IllegalArgumentException.class, () -> store.write(pageId, MemorySegment.ofArray(image(new Random(1), 200))));
        }
    }

    @Test
    void aSegmentIsNotSettledWhileAPageAllocatedInItIsUnwritten() {
        try (PageStore store = PageStore.open(directory, PAGE_SIZE, PAGES_PER_SEGMENT, List.of())) {
            long pending = store.allocate(PAGE_SIZE);
            int segment = store.segmentOf(pending);
            fillPastOneSegment(store);
            assertFalse(store.settled(segment));
            store.write(pending, MemorySegment.ofArray(image(new Random(3), PAGE_SIZE)));
            assertTrue(store.settled(segment));
        }
    }

    @Test
    void evacuatingASegmentMovesOnlyThePagesTheDirectoryStillPointsAt() {
        try (PageStore store = PageStore.open(directory, PAGE_SIZE, PAGES_PER_SEGMENT, List.of())) {
            Map<Long, byte[]> kept = new LinkedHashMap<>();
            List<Long> released = new ArrayList<>();
            int segment = store.activeSegment() == 0 ? 1 : store.activeSegment();
            Random random = new Random(9);
            while (store.activeSegment() <= segment) {
                int length = PageHeader.SIZE + random.nextInt(PAGE_SIZE - PageHeader.SIZE + 1);
                long pageId = store.allocate(length);
                byte[] image = page(random, pageId, length);
                store.write(pageId, MemorySegment.ofArray(image));
                if (store.segmentOf(pageId) != segment) {
                    kept.put(pageId, image);
                } else if (random.nextInt(3) == 0) {
                    store.release(pageId);
                    released.add(pageId);
                } else {
                    kept.put(pageId, image);
                }
            }
            store.journal(1);
            store.applyJournal();
            long before = store.liveBytes().get(segment);
            long moved = store.evacuate(segment);
            assertTrue(moved > 0 && moved <= before, "moved " + moved + " of " + before);
            assertEquals(null, store.liveBytes().get(segment));
            kept.forEach((pageId, image) -> {
                assertNotEquals(segment, store.segmentOf(pageId));
                assertArrayEquals(image, bytes(store.read(pageId)));
            });
            released.forEach(pageId -> assertEquals(PageAddress.NONE, store.addressOrNone(pageId)));
            assertFalse(released.isEmpty());
        }
    }

    private static byte[] page(Random random, long pageId, int length) {
        byte[] image = new byte[length];
        random.nextBytes(image);
        new PageHeader(PageType.LEAF, 1, 0, pageId, 1, length - PageHeader.SIZE, 0, 0, 0, 0, 0, 0).writeTo(ByteCursor.wrap(image));
        PageHeader.assign(MemorySegment.ofArray(image), pageId);
        return image;
    }

    @Test
    void readersKeepSeeingWholePagesWhileOldMapsAreClosed() throws Exception {
        int pages = 6_000;
        long[] ids = new long[pages];
        byte[][] images = new byte[pages][];
        AtomicInteger written = new AtomicInteger();
        try (PageStore store = PageStore.open(directory, PAGE_SIZE, PAGES_PER_SEGMENT, List.of());
             ExecutorService readers = Executors.newFixedThreadPool(4)) {
            List<Future<Long>> checks = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                Random random = new Random(t);
                checks.add(readers.submit(() -> {
                    long reads = 0;
                    while (written.get() < pages) {
                        int known = written.get();
                        if (known > 0) {
                            int i = random.nextInt(known);
                            assertArrayEquals(images[i], bytes(store.read(ids[i])), "page " + i);
                            reads++;
                        }
                    }
                    return reads;
                }));
            }
            Random random = new Random(11);
            for (int i = 0; i < pages; i++) {
                images[i] = image(random, PAGE_SIZE - random.nextInt(64));
                ids[i] = store.allocate(images[i].length);
                store.write(ids[i], MemorySegment.ofArray(images[i]));
                written.incrementAndGet();
            }
            for (Future<Long> check : checks) {
                assertTrue(check.get() > 0);
            }
        }
    }

    private static long write(PageStore store, int length) {
        byte[] image = image(new Random(length), length);
        long pageId = store.allocate(image.length);
        store.write(pageId, MemorySegment.ofArray(image));
        return pageId;
    }

    private static void fillPastOneSegment(PageStore store) {
        int segment = store.activeSegment();
        while (store.activeSegment() == segment) {
            write(store, PAGE_SIZE);
        }
    }

    private static byte[] image(Random random, int length) {
        byte[] image = new byte[length];
        random.nextBytes(image);
        ByteCursor.wrap(image).position(PAYLOAD_LENGTH_OFFSET).putInt(length - PageHeader.SIZE);
        return image;
    }

    private static byte[] bytes(MemorySegment page) {
        return page.toArray(ValueLayout.JAVA_BYTE);
    }
}
