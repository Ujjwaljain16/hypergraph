// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

public final class NodeCache {

    private static final int MAX_SHARDS = 16;
    private static final long PAGES_PER_SHARD = 64;

    private final Shard[] shards;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public NodeCache(long capacityBytes, int pageSize) {
        shards = new Shard[shardsFor(capacityBytes, pageSize)];
        long perShard = Math.max(1, capacityBytes / shards.length);
        for (int i = 0; i < shards.length; i++) {
            shards[i] = new Shard(perShard);
        }
    }

    static int shardsFor(long capacityBytes, int pageSize) {
        long fit = capacityBytes / (pageSize * PAGES_PER_SHARD);
        return Math.clamp(Long.highestOneBit(fit), 1, MAX_SHARDS);
    }

    public Node get(long pageId) {
        Node node = shard(pageId).get(pageId);
        (node == null ? misses : hits).increment();
        return node;
    }

    public void put(long pageId, Node node, int bytes) {
        shard(pageId).put(pageId, node, bytes);
    }

    public void clear() {
        for (Shard shard : shards) {
            shard.clear();
        }
    }

    public long hits() {
        return hits.sum();
    }

    public long misses() {
        return misses.sum();
    }

    public long residentBytes() {
        long total = 0;
        for (Shard shard : shards) {
            total += shard.bytes;
        }
        return total;
    }

    private Shard shard(long pageId) {
        return shards[(int) (Hashing.mix(pageId) >>> 60) & (shards.length - 1)];
    }

    private static final class Slot {
        final long pageId;
        final Node node;
        final int generation;
        int bytes;
        volatile boolean referenced;
        Slot previous;
        Slot next;

        Slot(long pageId, Node node, int bytes, int generation) {
            this.pageId = pageId;
            this.node = node;
            this.bytes = bytes;
            this.generation = generation;
        }
    }

    private static final class Shard {
        private static final int MIN_TABLE = 64;
        private static final Slot GONE = new Slot(0, null, 0, -1);
        private static final VarHandle SLOTS = MethodHandles.arrayElementVarHandle(Slot[].class);

        private final ReentrantLock admission = new ReentrantLock();
        private final long budget;
        private volatile Slot[] table = new Slot[MIN_TABLE];
        private volatile long bytes;
        private int live;
        private int gone;
        private int generation;
        private Slot hand;

        Shard(long budget) {
            this.budget = budget;
        }

        private static int home(long pageId, int mask) {
            return (int) Hashing.mix(pageId) & mask;
        }

        private Slot find(long pageId) {
            Slot[] slots = table;
            int mask = slots.length - 1;
            for (int i = home(pageId, mask); ; i = (i + 1) & mask) {
                Slot slot = (Slot) SLOTS.getAcquire(slots, i);
                if (slot == null) {
                    return null;
                }
                if (slot != GONE && slot.pageId == pageId) {
                    return slot;
                }
            }
        }

        private void insert(Slot slot) {
            if ((live + gone + 1) * 2 > table.length) {
                rebuild(Math.max(MIN_TABLE, Integer.highestOneBit(Math.max(1, live + 1) * 4)));
            }
            Slot[] slots = table;
            int mask = slots.length - 1;
            int i = home(slot.pageId, mask);
            while (slots[i] != null && slots[i] != GONE) {
                i = (i + 1) & mask;
            }
            if (slots[i] == GONE) {
                gone--;
            }
            SLOTS.setRelease(slots, i, slot);
            live++;
        }

        private void remove(Slot slot) {
            Slot[] slots = table;
            int mask = slots.length - 1;
            for (int i = home(slot.pageId, mask); slots[i] != null; i = (i + 1) & mask) {
                if (slots[i] == slot) {
                    SLOTS.setRelease(slots, i, GONE);
                    live--;
                    gone++;
                    return;
                }
            }
        }

        private void rebuild(int capacity) {
            Slot[] fresh = new Slot[capacity];
            int mask = capacity - 1;
            for (Slot slot : table) {
                if (slot != null && slot != GONE) {
                    int i = home(slot.pageId, mask);
                    while (fresh[i] != null) {
                        i = (i + 1) & mask;
                    }
                    fresh[i] = slot;
                }
            }
            gone = 0;
            table = fresh;
        }

        Node get(long pageId) {
            Slot slot = find(pageId);
            if (slot == null) {
                return null;
            }
            if (!slot.referenced) {
                slot.referenced = true;
            }
            return slot.node;
        }

        void put(long pageId, Node node, int weight) {
            admission.lock();
            try {
                Slot existing = find(pageId);
                if (existing != null && existing.node == node) {
                    return;
                }
                if (existing != null) {
                    unlink(existing);
                    remove(existing);
                }
                if (weight > budget) {
                    return;
                }
                while (bytes + weight > budget) {
                    evict();
                }
                Slot slot = new Slot(pageId, node, weight, generation);
                link(slot);
                insert(slot);
                node.chargeTo(grown -> charge(slot, grown));
            } finally {
                admission.unlock();
            }
        }

        private void charge(Slot slot, int grown) {
            admission.lock();
            try {
                if (slot.next == null || slot.generation != generation) {
                    return;
                }
                slot.bytes += grown;
                bytes += grown;
                while (bytes > budget && hand != null) {
                    evict();
                }
            } finally {
                admission.unlock();
            }
        }

        private void evict() {
            while (hand.referenced) {
                hand.referenced = false;
                hand = hand.next;
            }
            Slot victim = hand;
            unlink(victim);
            remove(victim);
        }

        private void link(Slot slot) {
            if (hand == null) {
                slot.previous = slot;
                slot.next = slot;
                hand = slot;
            } else {
                slot.next = hand;
                slot.previous = hand.previous;
                hand.previous.next = slot;
                hand.previous = slot;
            }
            bytes += slot.bytes;
        }

        private void unlink(Slot slot) {
            if (slot.next == slot) {
                hand = null;
            } else {
                slot.previous.next = slot.next;
                slot.next.previous = slot.previous;
                if (hand == slot) {
                    hand = slot.next;
                }
            }
            slot.previous = null;
            slot.next = null;
            bytes -= slot.bytes;
        }

        void clear() {
            admission.lock();
            try {
                table = new Slot[MIN_TABLE];
                live = 0;
                gone = 0;
                generation++;
                hand = null;
                bytes = 0;
            } finally {
                admission.unlock();
            }
        }
    }
}
