// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public final class TreeDiff {

    public sealed interface Delta<V> {
        long key();

        record Added<V>(long key, V value) implements Delta<V> {
        }

        record Removed<V>(long key, V value) implements Delta<V> {
        }

        record Changed<V>(long key, V before, V after) implements Delta<V> {
        }
    }

    private TreeDiff() {
    }

    public static <V> Stream<Delta<V>> diff(Tree<V> before, Tree<V> after) {
        return Tree.stream(new Walker<>(before, after)).map(Entry::value);
    }

    public static <V> Optional<String> difference(Tree<V> a, Tree<V> b) {
        if (!a.summary().equals(b.summary())) {
            return Optional.of("summaries differ: " + a.summary() + " and " + b.summary());
        }
        Iterator<Entry<V>> left = a.stream().iterator();
        Iterator<Entry<V>> right = b.stream().iterator();
        while (left.hasNext() && right.hasNext()) {
            Entry<V> x = left.next();
            Entry<V> y = right.next();
            if (x.key() != y.key()) {
                return Optional.of("keys differ: " + x.key() + " and " + y.key());
            }
            Optional<String> value = valueDifference(a, b, x.value(), y.value());
            if (value.isPresent()) {
                return Optional.of("key " + x.key() + ": " + value.get());
            }
        }
        if (left.hasNext() || right.hasNext()) {
            return Optional.of("one tree has more entries");
        }
        return Optional.empty();
    }

    private static <V> Optional<String> valueDifference(Tree<V> a, Tree<V> b, V x, V y) {
        ValueCodec<V> codec = a.schema().codec();
        if (!codec.holdsRefs()) {
            return Objects.equals(x, y) ? Optional.empty() : Optional.of(x + " and " + y);
        }
        if (!Objects.equals(codec.mapRefs(x, (_, _) -> Ref.EMPTY), codec.mapRefs(y, (_, _) -> Ref.EMPTY))) {
            return Optional.of(x + " and " + y);
        }
        List<Tree<?>> nestedX = new ArrayList<>();
        List<Tree<?>> nestedY = new ArrayList<>();
        codec.forEachRef(x, (ref, schema) -> nestedX.add(new Tree<>(schema, a.source(), ref)));
        codec.forEachRef(y, (ref, schema) -> nestedY.add(new Tree<>(schema, b.source(), ref)));
        for (int i = 0; i < nestedX.size(); i++) {
            Optional<String> nested = nestedDifference(nestedX.get(i), nestedY.get(i));
            if (nested.isPresent()) {
                return Optional.of("nested tree " + i + ", " + nested.get());
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static <V> Optional<String> nestedDifference(Tree<V> x, Tree<?> y) {
        return difference(x, (Tree<V>) y);
    }

    private sealed interface Item {
        record Subtree(Ref ref) implements Item {
        }

        record Element(long key, Object value) implements Item {
        }
    }

    private static final class Walker<V> implements Iterator<Entry<Delta<V>>> {
        private final Tree<V> before;
        private final Tree<V> after;
        private final Deque<Item> left = new ArrayDeque<>();
        private final Deque<Item> right = new ArrayDeque<>();
        private Delta<V> pending;

        Walker(Tree<V> before, Tree<V> after) {
            this.before = before;
            this.after = after;
            if (!before.isEmpty()) {
                left.push(new Item.Subtree(before.root()));
            }
            if (!after.isEmpty()) {
                right.push(new Item.Subtree(after.root()));
            }
        }

        @Override
        public boolean hasNext() {
            while (pending == null && (!left.isEmpty() || !right.isEmpty())) {
                pending = step();
            }
            return pending != null;
        }

        @Override
        public Entry<Delta<V>> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Delta<V> delta = pending;
            pending = null;
            return new Entry<>(delta.key(), delta);
        }

        private Delta<V> step() {
            Item a = left.peek();
            Item b = right.peek();
            if (a == null) {
                return drain(right, after, false);
            }
            if (b == null) {
                return drain(left, before, true);
            }
            return switch (a) {
                case Item.Subtree(Ref ra) when b instanceof Item.Subtree(Ref rb) -> compareSubtrees(ra, rb);
                case Item.Subtree(Ref ra) when b instanceof Item.Element(long key, Object value) -> {
                    if (ra.summary().min() > key) {
                        right.pop();
                        yield new Delta.Added<>(key, after.schema().cast(value));
                    }
                    expand(left, before);
                    yield null;
                }
                case Item.Element(long key, Object value) when b instanceof Item.Subtree(Ref rb) -> {
                    if (rb.summary().min() > key) {
                        left.pop();
                        yield new Delta.Removed<>(key, before.schema().cast(value));
                    }
                    expand(right, after);
                    yield null;
                }
                case Item.Element(long ka, Object va) when b instanceof Item.Element(long kb, Object vb) -> compareElements(ka, va, kb, vb);
                default -> throw new IllegalStateException();
            };
        }

        private Delta<V> compareSubtrees(Ref ra, Ref rb) {
            if (Ref.same(ra, rb)) {
                left.pop();
                right.pop();
                return null;
            }
            Summary sa = ra.summary();
            Summary sb = rb.summary();
            if (sa.max() < sb.min()) {
                expand(left, before);
            } else if (sb.max() < sa.min()) {
                expand(right, after);
            } else if (sa.count() > sb.count()) {
                expand(left, before);
            } else if (sb.count() > sa.count()) {
                expand(right, after);
            } else {
                expand(left, before);
                expand(right, after);
            }
            return null;
        }

        private Delta<V> compareElements(long ka, Object va, long kb, Object vb) {
            if (ka < kb) {
                left.pop();
                return new Delta.Removed<>(ka, before.schema().cast(va));
            }
            if (kb < ka) {
                right.pop();
                return new Delta.Added<>(kb, after.schema().cast(vb));
            }
            left.pop();
            right.pop();
            return Objects.equals(va, vb) ? null : new Delta.Changed<>(ka, before.schema().cast(va), after.schema().cast(vb));
        }

        private Delta<V> drain(Deque<Item> side, Tree<V> tree, boolean removed) {
            while (side.peek() instanceof Item.Subtree) {
                expand(side, tree);
            }
            if (side.isEmpty()) {
                return null;
            }
            Item.Element element = (Item.Element) side.pop();
            V value = tree.schema().cast(element.value());
            return removed ? new Delta.Removed<>(element.key(), value) : new Delta.Added<>(element.key(), value);
        }

        private void expand(Deque<Item> side, Tree<V> tree) {
            Item.Subtree subtree = (Item.Subtree) side.pop();
            switch (tree.node(subtree.ref())) {
                case Leaf leaf -> {
                    for (int i = leaf.size() - 1; i >= 0; i--) {
                        side.push(new Item.Element(leaf.key(i), leaf.value(i)));
                    }
                }
                case Branch branch -> {
                    for (int i = branch.size() - 1; i >= 0; i--) {
                        side.push(new Item.Subtree(branch.child(i)));
                    }
                }
            }
        }
    }
}
