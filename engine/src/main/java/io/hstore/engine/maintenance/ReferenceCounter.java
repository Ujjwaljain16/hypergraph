// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.maintenance;

import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.Generation;
import io.hstore.engine.catalog.RootVector;
import io.hstore.engine.catalog.SlotRegistry;
import io.hstore.engine.page.LongLongMap;
import io.hstore.engine.page.PageAddress;
import io.hstore.engine.page.PageId;
import io.hstore.engine.page.PageStore;
import io.hstore.engine.page.ReferenceCounts;
import io.hstore.engine.tree.PagedNodeSource;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.Summary;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.TreeWalker;
import io.hstore.engine.txn.ReferenceTracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static io.hstore.engine.tree.TreeWalker.UNKNOWN_HEIGHT;

public final class ReferenceCounter implements ReferenceTracker, AutoCloseable {

    public record Verification(long livePages, long waitingPages) {
    }

    private sealed interface Change {
    }

    private record Discarded(long[] spilled) implements Change {
    }

    private record Provisional(long generation, long[] pages) {
    }

    private static final System.Logger LOG = System.getLogger("hstore.references");
    private static final int QUEUE_LIMIT = 4096;

    private final PageStore pages;
    private final ReferenceCounts counts;
    private final TreeWalker walker;
    private final SlotRegistry slots;
    private final LinkedBlockingQueue<Change> queue = new LinkedBlockingQueue<>(QUEUE_LIMIT);
    private final ConcurrentLinkedQueue<Provisional> provisional = new ConcurrentLinkedQueue<>();
    private final Semaphore ready = new Semaphore(0);
    private final ReentrantLock processing = new ReentrantLock();
    private final LongLongMap deltas = new LongLongMap(1 << 10);
    private final Work work = new Work();
    private final Work lost = new Work();
    private final Limbo limbo = new Limbo();
    private final TreeWalker.Reference gaining = this::gain;
    private final TreeWalker.Reference losing = this::lose;
    private long generation;
    private long accounting;
    private volatile Throwable broken;
    private volatile boolean closed;
    private Thread worker;

    private ReferenceCounter(PageStore pages, PagedNodeSource source, SlotRegistry slots, long generation) {
        this.pages = pages;
        this.counts = pages.references();
        this.walker = new TreeWalker(source.uncached());
        this.slots = slots;
        this.generation = generation;
    }

    public static ReferenceCounter recover(PageStore pages, PagedNodeSource source, SlotRegistry slots, Generation checkpointed,
                                           Collection<Generation> retained, long head) {
        ReferenceCounter counter = new ReferenceCounter(pages, source, slots, checkpointed.id());
        counter.catchUp(checkpointed, retained, head);
        return counter;
    }

    private void catchUp(Generation checkpointed, Collection<Generation> retained, long head) {
        if (!counts.trusted()) {
            long started = System.nanoTime();
            rebuild(retained, checkpointed);
            LOG.log(System.Logger.Level.INFO, "rebuilt reference counts in {0} ms", (System.nanoTime() - started) / 1_000_000);
        }
        PageStore.Scan scan = pages.scan();
        limbo.restore(scan.dead());
        if (scan.unreferenced().length > 0) {
            provisional.add(new Provisional(head, scan.unreferenced()));
            LOG.log(System.Logger.Level.INFO, "{0} pages are not counted yet; the first checkpoint counts them or releases them",
                    scan.unreferenced().length);
        }
        if (scan.unplaced() > 0) {
            LOG.log(System.Logger.Level.WARNING, "{0} referenced pages have no address", scan.unplaced());
        }
    }

    public void start() {
        worker = Thread.ofPlatform().name("hstore-references").daemon(true).start(this::run);
    }

    @Override
    public void provisional(long generation, long[] pages) {
        if (pages.length > 0) {
            provisional.add(new Provisional(generation, pages));
        }
    }

    public void checkpointed(Generation before, Generation after) {
        if (!processing.isHeldByCurrentThread()) {
            throw new IllegalStateException("reference counts change at a checkpoint only while counting is paused");
        }
        if (broken != null) {
            return;
        }
        try {
            account(before, after, provisionalThrough(after.id()));
        } catch (RuntimeException | Error failure) {
            broken = failure;
            LOG.log(System.Logger.Level.ERROR, "reference counting stopped; the counts will be rebuilt when the database next opens", failure);
            counts.distrust();
        }
    }

    private long[] provisionalThrough(long generation) {
        LongStream.Builder pages = LongStream.builder();
        for (Iterator<Provisional> entries = provisional.iterator(); entries.hasNext(); ) {
            Provisional entry = entries.next();
            if (entry.generation() <= generation) {
                Arrays.stream(entry.pages()).forEach(pages::add);
                entries.remove();
            }
        }
        return pages.build().toArray();
    }

    public boolean isProvisional(long pageId) {
        return provisional.stream().anyMatch(entry -> Arrays.stream(entry.pages()).anyMatch(page -> page == pageId));
    }

    @Override
    public void discarded(long[] spilled) {
        enqueue(new Discarded(spilled));
    }

    private void enqueue(Change change) {
        if (closed) {
            return;
        }
        boolean interrupted = false;
        while (true) {
            try {
                queue.put(change);
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        ready.release();
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        while (!closed) {
            try {
                ready.acquire();
            } catch (InterruptedException e) {
                return;
            }
            ready.drainPermits();
            processing.lock();
            try {
                drain();
            } finally {
                processing.unlock();
            }
        }
    }

    private void drain() {
        for (Change change = queue.poll(); change != null; change = queue.poll()) {
            if (broken != null) {
                continue;
            }
            try {
                switch (change) {
                    case Discarded(long[] spilled) -> orphaned(spilled, generation);
                }
            } catch (RuntimeException | Error failure) {
                broken = failure;
                LOG.log(System.Logger.Level.ERROR, "reference counting stopped; the counts will be rebuilt when the database next opens", failure);
                counts.distrust();
            }
        }
    }

    public <T> T quiesce(Supplier<T> action) {
        pause();
        try {
            return action.get();
        } finally {
            resume();
        }
    }

    public void pause() {
        processing.lock();
        try {
            drain();
        } catch (RuntimeException | Error failure) {
            processing.unlock();
            throw failure;
        }
    }

    public void resume() {
        processing.unlock();
    }

    public int pending() {
        return counts.changed();
    }

    public long waiting() {
        return limbo.size();
    }

    public long release(long oldestReadable) {
        if (broken != null || !counts.trusted()) {
            return 0;
        }
        long released = 0;
        while (!limbo.isEmpty() && limbo.headGeneration() <= oldestReadable) {
            long since = limbo.headGeneration();
            long index = limbo.headIndex();
            limbo.pop();
            if (counts.entry(index) == ReferenceCounts.deadSince(since)) {
                counts.set(index, 0);
                pages.release(PageId.pack(index, 0));
                released++;
            }
        }
        return released;
    }

    private void account(Generation before, Generation after, long[] spilled) {
        accounting = after.id();
        deltas.clear();
        work.clear();
        lost.clear();
        after.branches().forEach((branch, now) -> compare(roots(before, branch), now.roots()));
        before.branches().forEach((branch, was) -> {
            if (!after.branches().containsKey(branch)) {
                compare(was.roots(), RootVector.EMPTY);
            }
        });
        while (!work.isEmpty()) {
            work.pop();
            walker.references(work.page, work.schema, work.height, gaining);
        }
        while (!lost.isEmpty()) {
            lost.pop();
            lose(lost.page, lost.schema, lost.height);
        }
        while (!work.isEmpty()) {
            work.pop();
            walker.references(work.page, work.schema, work.height, losing);
        }
        deltas.forEach((index, delta) -> {
            long entry = counts.entry(index);
            long base = ReferenceCounts.count(entry);
            long total = base + delta;
            if (total > 0) {
                if (total != entry) {
                    counts.set(index, total);
                }
            } else if (base > 0) {
                die(index);
            }
        });
        orphaned(spilled, accounting);
        generation = accounting;
    }

    private void compare(RootVector was, RootVector now) {
        if (was == now) {
            return;
        }
        now.roots().forEach((slot, next) -> {
            Ref previous = was.get(slot);
            if (!Ref.same(previous, next)) {
                changed(slot, previous, next);
            }
        });
        was.roots().forEach((slot, previous) -> {
            if (!now.roots().containsKey(slot)) {
                changed(slot, previous, Ref.EMPTY);
            }
        });
    }

    private void changed(int slot, Ref previous, Ref next) {
        TreeSchema<?> schema = slots.slot(slot).schema();
        if (next instanceof Ref.Stored(long pageId, int _, Summary _)) {
            gain(pageId, schema, UNKNOWN_HEIGHT);
        }
        if (previous instanceof Ref.Stored(long pageId, int _, Summary _)) {
            lost.push(pageId, schema, UNKNOWN_HEIGHT);
        }
    }

    private static RootVector roots(Generation generation, int branch) {
        Branch found = generation.branches().get(branch);
        return found == null ? RootVector.EMPTY : found.roots();
    }

    private void gain(long pageId, TreeSchema<?> schema, int height) {
        long index = PageId.indexOf(pageId);
        if (deltas.add(index, 1) == 1 && ReferenceCounts.count(counts.entry(index)) == 0) {
            work.push(pageId, schema, height);
        }
    }

    private void lose(long pageId, TreeSchema<?> schema, int height) {
        long index = PageId.indexOf(pageId);
        long base = ReferenceCounts.count(counts.entry(index));
        if (base == 0) {
            throw inconsistent(pageId, "lost a reference it never had");
        }
        if (deltas.containsKey(index)) {
            long remaining = base + deltas.add(index, -1);
            if (remaining < 0) {
                throw inconsistent(pageId, "lost more references than it had");
            }
            if (remaining == 0) {
                work.push(pageId, schema, height);
            }
        } else if (base == 1) {
            die(index);
            work.push(pageId, schema, height);
        } else {
            counts.set(index, base - 1);
        }
    }

    private void die(long index) {
        counts.set(index, ReferenceCounts.deadSince(accounting));
        limbo.add(accounting, index);
    }

    private void orphaned(long[] spilled, long since) {
        for (long pageId : spilled) {
            long index = PageId.indexOf(pageId);
            if (counts.entry(index) == 0) {
                counts.set(index, ReferenceCounts.deadSince(since));
                limbo.add(since, index);
            }
        }
    }

    private static HStoreException inconsistent(long pageId, String problem) {
        return HStoreException.corrupt(pageId, "reference count of page " + PageId.unpack(pageId) + " " + problem);
    }

    private void rebuild(Collection<Generation> retained, Generation current) {
        counts.reset(pages.pageNumbers());
        work.clear();
        forEachRoot(current, (pageId, schema, height) -> counted(pageId, schema, height));
        while (!work.isEmpty()) {
            work.pop();
            walker.references(work.page, work.schema, work.height, this::counted);
        }
        long since = current.id();
        TreeWalker.Reference retainedOnly = (pageId, schema, height) -> {
            long index = PageId.indexOf(pageId);
            if (counts.entry(index) == 0) {
                counts.store(index, ReferenceCounts.deadSince(since));
                work.push(pageId, schema, height);
            }
        };
        for (Generation generation : retained) {
            forEachRoot(generation, retainedOnly);
            while (!work.isEmpty()) {
                work.pop();
                walker.references(work.page, work.schema, work.height, retainedOnly);
            }
        }
        counts.rebuilt();
    }

    private void counted(long pageId, TreeSchema<?> schema, int height) {
        long index = PageId.indexOf(pageId);
        long count = counts.entry(index);
        counts.store(index, count + 1);
        if (count == 0) {
            work.push(pageId, schema, height);
        }
    }

    private void forEachRoot(Generation generation, TreeWalker.Reference into) {
        generation.branches().values().forEach(branch -> branch.roots().roots().forEach((slot, ref) -> {
            if (ref instanceof Ref.Stored(long pageId, int _, Summary _)) {
                into.accept(pageId, slots.slot(slot).schema(), UNKNOWN_HEIGHT);
            }
        }));
    }

    public Verification verify(Generation current, Collection<Generation> retained) {
        return quiesce(() -> {
            LongLongMap expected = new LongLongMap(1 << 12);
            Work pending = new Work();
            TreeWalker.Reference count = (pageId, schema, height) -> {
                if (expected.add(PageId.indexOf(pageId), 1) == 1) {
                    pending.push(pageId, schema, height);
                }
            };
            forEachRoot(current, count);
            while (!pending.isEmpty()) {
                pending.pop();
                walker.references(pending.page, pending.schema, pending.height, count);
            }
            List<String> problems = new ArrayList<>();
            long live = 0;
            long waiting = 0;
            long end = pages.pageNumbers();
            for (long index = 1; index < end; index++) {
                long entry = counts.entry(index);
                long references = expected.get(index, 0);
                if (references > 0) {
                    live++;
                    if (entry != references) {
                        problems.add("page #" + index + " is counted " + describe(entry) + " but has " + references + " references");
                    }
                } else if (entry > 0) {
                    problems.add("page #" + index + " is counted " + entry + " but nothing refers to it");
                } else if (entry < 0) {
                    waiting++;
                }
            }
            LongLongMap seen = new LongLongMap(1 << 12);
            long[] reader = new long[1];
            TreeWalker.Reference readable = (pageId, schema, height) -> {
                long index = PageId.indexOf(pageId);
                if (seen.add(index, 1) != 1) {
                    return;
                }
                long entry = counts.entry(index);
                if (entry == 0 && !isProvisional(pageId)) {
                    problems.add("page #" + index + " is still readable from generation " + reader[0] + " but is not counted");
                } else if (ReferenceCounts.dead(entry) && ReferenceCounts.deadAt(entry) <= reader[0]) {
                    problems.add("page #" + index + " is readable from generation " + reader[0] + " but counted " + describe(entry));
                } else if (pages.addressOrNone(pageId) == PageAddress.NONE) {
                    problems.add("page #" + index + " is still readable from generation " + reader[0] + " but has no address");
                } else {
                    pending.push(pageId, schema, height);
                }
            };
            for (Generation generation : retained.stream().sorted(Comparator.comparingLong(Generation::id)).toList()) {
                reader[0] = generation.id();
                generation.branches().values().forEach(branch -> branch.roots().roots().forEach((slot, ref) ->
                        TreeWalker.forEachStored(ref, slots.slot(slot).schema(), readable)));
                while (!pending.isEmpty()) {
                    pending.pop();
                    walker.references(pending.page, pending.schema, pending.height, readable);
                }
            }
            if (!problems.isEmpty()) {
                throw HStoreException.invalid(problems.size() + " reference count problems, first: " + String.join("; ", problems.subList(0, Math.min(5, problems.size()))));
            }
            return new Verification(live, waiting);
        });
    }

    private static String describe(long entry) {
        return ReferenceCounts.dead(entry) ? "dead since generation " + ReferenceCounts.deadAt(entry) : Long.toString(entry);
    }

    @Override
    public void close() {
        closed = true;
        ready.release();
        if (worker != null) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class Work {
        private long[] pages = new long[256];
        private TreeSchema<?>[] schemas = new TreeSchema<?>[256];
        private int[] heights = new int[256];
        private int size;
        long page;
        TreeSchema<?> schema;
        int height;

        void push(long pageId, TreeSchema<?> pageSchema, int pageHeight) {
            if (size == pages.length) {
                pages = Arrays.copyOf(pages, size * 2);
                schemas = Arrays.copyOf(schemas, size * 2);
                heights = Arrays.copyOf(heights, size * 2);
            }
            pages[size] = pageId;
            schemas[size] = pageSchema;
            heights[size] = pageHeight;
            size++;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void pop() {
            size--;
            page = pages[size];
            schema = schemas[size];
            height = heights[size];
            schemas[size] = null;
        }

        void clear() {
            Arrays.fill(schemas, 0, size, null);
            size = 0;
        }
    }

    private static final class Limbo {
        private long[] generations = new long[256];
        private long[] indexes = new long[256];
        private int head;
        private int tail;

        void add(long since, long index) {
            if (tail == generations.length) {
                int size = tail - head;
                boolean grow = size * 2 >= generations.length;
                long[] movedGenerations = grow ? new long[generations.length * 2] : generations;
                long[] movedIndexes = grow ? new long[indexes.length * 2] : indexes;
                System.arraycopy(generations, head, movedGenerations, 0, size);
                System.arraycopy(indexes, head, movedIndexes, 0, size);
                generations = movedGenerations;
                indexes = movedIndexes;
                head = 0;
                tail = size;
            }
            generations[tail] = since;
            indexes[tail] = index;
            tail++;
        }

        boolean isEmpty() {
            return head == tail;
        }

        int size() {
            return tail - head;
        }

        long headGeneration() {
            return generations[head];
        }

        long headIndex() {
            return indexes[head];
        }

        void pop() {
            head++;
            if (head == tail) {
                head = 0;
                tail = 0;
            }
        }

        void restore(long[] pairs) {
            head = 0;
            tail = 0;
            int count = pairs.length / 2;
            IntStream.range(0, count).boxed()
                    .sorted(Comparator.comparingLong(i -> pairs[2 * i]))
                    .forEach(i -> add(pairs[2 * i], pairs[2 * i + 1]));
        }
    }
}
