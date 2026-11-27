package org.taniwha.privacy;

import org.taniwha.statistics.DateFeatureStatistics;
import org.taniwha.statistics.DateHistogramBin;
import org.taniwha.util.AggregateDisclosure;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

// Calendar intervals group sparse dates; summary moments and quantiles stay unchanged.
final class DateHistogramDisclosure {
    private DateHistogramDisclosure() {}

    static void release(DateFeatureStatistics feature) {
        var dates = feature.getDateHistogram();
        if (dates.values().stream().allMatch(count -> count >= AggregateDisclosure.MIN_COUNT)) return;
        Map<LocalDate, Long> weeks = new TreeMap<>();
        dates.forEach((date, count) -> weeks.merge(LocalDate.parse(date)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), count, Long::sum));
        long target = Math.max(AggregateDisclosure.MIN_COUNT,
                (feature.getCount() + AggregateDisclosure.MAX_CELLS - 1) / AggregateDisclosure.MAX_CELLS);
        List<DateHistogramBin> bins = new ArrayList<>();
        LocalDate first = null;
        long count = 0, remaining = feature.getCount();
        for (var week : weeks.entrySet()) {
            if (first == null) first = week.getKey();
            count += week.getValue();
            remaining -= week.getValue();
            if (count < target || remaining > 0 && remaining < target) continue;
            bins.add(new DateHistogramBin(first.toString(), week.getKey().plusDays(6).toString(), count));
            first = null;
            count = 0;
        }
        feature.coarsenHistogram(bins);
    }
}
