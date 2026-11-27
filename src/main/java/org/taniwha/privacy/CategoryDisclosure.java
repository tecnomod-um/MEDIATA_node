package org.taniwha.privacy;

import org.taniwha.statistics.CategoricalFeatureStatistics;
import org.taniwha.statistics.CategoryHistogramBin;
import org.taniwha.util.AggregateDisclosure;

import java.util.*;

// Protect small cells and their complements without discarding the feature summary.
final class CategoryDisclosure {
    private CategoryDisclosure() {}

    static void release(CategoricalFeatureStatistics feature) {
        var counts = feature.getCategoryCounts();
        if (counts.values().stream().allMatch(count -> count >= AggregateDisclosure.MIN_COUNT)) return;
        var entries = counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= AggregateDisclosure.MIN_COUNT)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).toList();
        List<CategoryHistogramBin> bins = new ArrayList<>();
        Set<String> published = new HashSet<>();
        long remaining = feature.getCount();
        for (var entry : entries) {
            if (bins.size() >= AggregateDisclosure.MAX_CELLS - 1) break;
            long rest = remaining - entry.getValue();
            if (entry.getValue() < AggregateDisclosure.MIN_COUNT || rest > 0 && rest < AggregateDisclosure.MIN_COUNT) continue;
            bins.add(new CategoryHistogramBin(entry.getKey(), entry.getValue(), false));
            published.add(entry.getKey());
            remaining = rest;
        }
        if (remaining > 0) {
            String label = "Other categories";
            for (int suffix = 2; published.contains(label); suffix++) label = "Other categories (" + suffix + ")";
            bins.add(new CategoryHistogramBin(label, remaining, true));
        }
        feature.coarsenDistribution(bins, published, AggregateDisclosure.MIN_COUNT);
    }
}
