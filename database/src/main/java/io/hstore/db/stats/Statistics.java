// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.stats;

import io.hstore.db.Atom;
import io.hstore.db.Reader;
import io.hstore.db.security.Principal;
import io.hstore.engine.StorageEngine;
import io.hstore.engine.catalog.AtomRecord.EdgeRecord;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.feed.ChangeFeed;
import io.hstore.engine.index.IndexValues.Incident;
import io.hstore.engine.index.Postings;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeAlgebra;
import io.hstore.engine.txn.Snapshot;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public final class Statistics implements AutoCloseable {

    public record Catalog(long generation, long atoms, long edges, boolean sampled, Histogram cardinality, Histogram degree,
                          Map<Long, Long> edgesByCardinality, Map<Integer, Long> typeCounts) {

        public long countWithCardinality(long k) {
            return edgesByCardinality.getOrDefault(k, 0L);
        }

        public double averageDegree() {
            return degree.mean();
        }
    }

    private record Feedback(double ratio, long observations) {
        Feedback absorb(double sample) {
            double weight = observations < 8 ? 1.0 / (observations + 1) : 0.2;
            return new Feedback(ratio * (1 - weight) + sample * weight, observations + 1);
        }
    }

    private static final int SAMPLE_LIMIT = 200_000;
    private static final long REFRESH_AFTER_CHANGES = 10_000;
    private static final long REFRESH_FRACTION = 10;

    private final StorageEngine engine;
    private final Map<String, Feedback> feedback = new ConcurrentHashMap<>();
    private final AtomicLong changesSinceRefresh = new AtomicLong();
    private final AtomicLong refreshes = new AtomicLong();
    private volatile long refreshAfter = REFRESH_AFTER_CHANGES;
    private final ChangeFeed.Subscription subscription;
    private volatile Catalog current;

    public Statistics(StorageEngine engine) {
        this.engine = engine;
        this.subscription = engine.feed().subscribe(engine.transactions().current().id(), event -> {
            if (changesSinceRefresh.addAndGet(event.members().size() + event.slots().size()) > refreshAfter) {
                refresh();
            }
        }, (_, _) -> refresh());
    }

    public Catalog catalog() {
        Catalog snapshot = current;
        return snapshot != null ? snapshot : refresh();
    }

    public Catalog refresh() {
        try (Snapshot snapshot = engine.snapshot()) {
            Tree<AtomRecord> atoms = snapshot.scan(EngineSlots.CATALOG);
            Tree<Postings<Incident>> reverse = snapshot.scan(EngineSlots.REVERSE);
            long total = atoms.size();
            boolean sampled = total > SAMPLE_LIMIT;
            long stride = sampled ? (total + SAMPLE_LIMIT - 1) / SAMPLE_LIMIT : 1;
            long[] cardinalities = atoms.stream()
                    .filter(entry -> entry.value() instanceof EdgeRecord)
                    .filter(entry -> entry.key() % stride == 0)
                    .mapToLong(entry -> ((EdgeRecord) entry.value()).cardinality())
                    .toArray();
            long[] degrees = atoms.keys()
                    .filter(atom -> atom % stride == 0)
                    .map(atom -> EngineSlots.REVERSE_INDEX.count(reverse, atom))
                    .toArray();
            Map<Long, Long> byCardinality = Arrays.stream(cardinalities).boxed()
                    .collect(Collectors.groupingBy(k -> k, TreeMap::new, Collectors.summingLong(_ -> stride)));
            Map<Integer, Long> types = snapshot.scan(EngineSlots.TYPES).stream()
                    .collect(Collectors.toMap(entry -> (int) entry.key(), entry -> entry.value().size(), Long::sum, TreeMap::new));
            long edges = cardinalities.length * stride;
            Histogram cardinality = Histogram.of(cardinalities);
            Catalog refreshed = new Catalog(snapshot.generation(), total, edges, sampled, cardinality,
                    Histogram.of(degrees), byCardinality, types);
            current = refreshed;
            refreshAfter = Math.max(REFRESH_AFTER_CHANGES, (total + cardinality.sum() * stride) / REFRESH_FRACTION);
            changesSinceRefresh.set(0);
            refreshes.incrementAndGet();
            return refreshed;
        }
    }

    long refreshes() {
        return refreshes.get();
    }

    long acknowledged() {
        return subscription.acknowledged();
    }

    public static long degree(Reader reader, long atom) {
        return reader.degree(atom);
    }

    public static long cardinality(Reader reader, long edge) {
        return reader.cardinality(edge);
    }

    public static double weight(Reader reader, long edge) {
        return reader.atom(edge).filter(Atom::isEdge).map(_ -> reader.edge(edge).summary().weightSum() / 1e9).orElse(0.0);
    }

    public static long overlap(Reader reader, long left, long right) {
        return TreeAlgebra.countIntersect(reader.edge(left).membership(), reader.edge(right).membership());
    }

    public void observe(int tenant, String accessPath, double estimated, long actual) {
        double sample = (actual + 1.0) / (Math.max(estimated, 0) + 1.0);
        feedback.merge(feedbackKey(tenant, accessPath), new Feedback(sample, 1), (existing, _) -> existing.absorb(sample));
    }

    public double correction(int tenant, String accessPath) {
        Feedback recorded = feedback.get(feedbackKey(tenant, accessPath));
        return recorded == null ? 1.0 : recorded.ratio();
    }

    private static String feedbackKey(int tenant, String accessPath) {
        return tenant == Principal.DEFAULT_TENANT ? accessPath : accessPath + " (tenant " + tenant + ")";
    }

    public Map<String, Double> corrections() {
        return feedback.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().ratio(), (a, _) -> a, TreeMap::new));
    }

    @Override
    public void close() {
        subscription.close();
    }
}
