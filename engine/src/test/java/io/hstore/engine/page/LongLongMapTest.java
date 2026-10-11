package io.hstore.engine.page;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LongLongMapTest {

    @Test
    void behavesLikeAHashMapThroughGrowthAndClears() {
        Random random = new Random(17);
        LongLongMap map = new LongLongMap(4);
        Map<Long, Long> model = new HashMap<>();
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < 50_000; i++) {
                long key = 1 + random.nextInt(round % 2 == 0 ? 20_000 : 2_000_000);
                long value = random.nextLong();
                if (random.nextBoolean()) {
                    map.put(key, value);
                    model.put(key, value);
                } else {
                    assertEquals(model.merge(key, value, Long::sum), map.add(key, value));
                }
                long probe = 1 + random.nextInt(2_000_000);
                assertEquals(model.getOrDefault(probe, -1L), map.get(probe, -1L));
                assertEquals(model.containsKey(probe), map.containsKey(probe));
            }
            assertEquals(model.size(), map.size());
            Map<Long, Long> seen = new HashMap<>();
            map.forEach(seen::put);
            assertEquals(model, seen);
            map.clear();
            model.clear();
            assertEquals(0, map.size());
            assertEquals(-1L, map.get(1, -1L));
        }
    }
}
