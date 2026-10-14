// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.bench;

import java.util.Arrays;
import java.util.stream.IntStream;

public final class DatasetStats {

    private static final long SEED = 20_260_904L;
    private static final int INLINE_POSTINGS = 48;

    private DatasetStats() {
    }

    public static void main(String[] args) {
        IntStream.of(1, 4).forEach(DatasetStats::print);
    }

    private static void print(int scale) {
        Dataset dataset = Dataset.generate(scale, SEED);
        int[] degree = new int[dataset.nodes()];
        int[] cardinality = Arrays.stream(dataset.edges()).mapToInt(edge -> edge.length).sorted().toArray();
        Arrays.stream(dataset.edges()).flatMapToInt(Arrays::stream).forEach(member -> degree[member]++);
        long incidences = IntStream.of(cardinality).asLongStream().sum();
        int[] sorted = degree.clone();
        Arrays.sort(sorted);
        IO.println("scale %d: %,d nodes, %,d hyperedges, %,d incidences".formatted(scale, dataset.nodes(), cardinality.length, incidences));
        IO.println("  cardinality mean %.2f, p99 %d, max %d".formatted(incidences / (double) cardinality.length,
                at(cardinality, 0.99), cardinality[cardinality.length - 1]));
        IO.println("  incidence set mean %.2f, p50 %d, p90 %d, p99 %d, p99.9 %d, max %d".formatted(incidences / (double) degree.length,
                at(sorted, 0.5), at(sorted, 0.9), at(sorted, 0.99), at(sorted, 0.999), sorted[sorted.length - 1]));
        IO.println("  nodes in no hyperedge %,d, incidence sets above %d entries %,d".formatted(
                IntStream.of(degree).filter(d -> d == 0).count(), INLINE_POSTINGS, IntStream.of(degree).filter(d -> d > INLINE_POSTINGS).count()));
    }

    private static int at(int[] sorted, double quantile) {
        return sorted[(int) (sorted.length * quantile)];
    }
}
