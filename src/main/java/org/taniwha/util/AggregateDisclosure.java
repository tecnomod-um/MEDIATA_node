package org.taniwha.util;

import org.taniwha.statistics.NumericValueCount;
import java.util.*;

public final class AggregateDisclosure {
    public static final int MIN_COUNT = 6;
    public static final int MAX_CELLS = 200;
    private AggregateDisclosure() {}

    public static List<NumericValueCount> numericFrequencies(List<Double> values) {
        Map<Double, Long> counts = new TreeMap<>();
        for (Double value : values) {
            if (value == null || !Double.isFinite(value)) return List.of();
            counts.merge(value, 1L, Long::sum);
            if (counts.size() > MAX_CELLS) return List.of();
        }
        if (counts.values().stream().anyMatch(count -> count < MIN_COUNT)) return List.of();
        return counts.entrySet().stream().map(entry -> new NumericValueCount(entry.getKey(), entry.getValue())).toList();
    }
}
