// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeSearchTest {

    private static long[] keys(RandomGenerator random, int size, int shape) {
        return switch (shape) {
            case 0 -> LongStream.range(0, size).map(i -> 1_000 + i).toArray();
            case 1 -> random.longs(size, -1_000_000, 1_000_000).distinct().sorted().toArray();
            case 2 -> LongStream.range(0, size).map(i -> i * i * i).toArray();
            default -> LongStream.concat(LongStream.range(0, size / 2), LongStream.range(0, size - size / 2).map(i -> Long.MAX_VALUE / 2 + i)).toArray();
        };
    }

    private static long probe(RandomGenerator random, long[] keys) {
        long span = keys[keys.length - 1] - keys[0];
        return switch (random.nextInt(3)) {
            case 0 -> keys[random.nextInt(keys.length)];
            case 1 -> keys[random.nextInt(keys.length)] + (random.nextBoolean() ? 1 : -1);
            default -> keys[0] - 5 + (long) (random.nextDouble() * (span + 10));
        };
    }

    private static int route(long[] separators, int size, long key) {
        int result = 0;
        for (int i = 1; i < size; i++) {
            if (separators[i] <= key) {
                result = i;
            }
        }
        return result;
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void interpolationFindsWhatABinarySearchFinds(int shape) {
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(shape);
        for (int round = 0; round < 400; round++) {
            long[] keys = keys(random, 1 + random.nextInt(600), shape);
            int size = keys.length;
            Leaf leaf = Leaf.owned(TreeTest.LONGS, null, keys, Arrays.stream(keys).boxed().toArray(), size);
            Ref[] children = new Ref[size];
            Arrays.fill(children, Ref.EMPTY);
            Branch branch = Branch.owned(TreeTest.LONGS, null, keys.clone(), children, size, 1);
            for (int i = 0; i < 200; i++) {
                long key = probe(random, keys);
                assertEquals(Arrays.binarySearch(keys, 0, size, key), leaf.search(key), "leaf search for " + key);
                assertEquals(route(keys, size, key), branch.route(key), "branch route for " + key);
            }
        }
    }
}
