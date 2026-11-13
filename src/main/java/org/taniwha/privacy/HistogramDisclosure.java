package org.taniwha.privacy;

import org.taniwha.statistics.ContinuousFeatureStatistics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.taniwha.util.AggregateDisclosure;

// Retains the finest permitted adjacent ranges without releasing protected cells
final class HistogramDisclosure {
    private HistogramDisclosure() {}

    static void release(ContinuousFeatureStatistics feature) {
        List<Double> bins = feature.getHistogram();
        double total = bins.stream().mapToDouble(Double::doubleValue).sum();
        if (bins.isEmpty() || bins.size() != feature.getBinRanges().size()
                || total != feature.getCount() || total < AggregateDisclosure.MIN_COUNT
                || bins.stream().anyMatch(count -> !Double.isFinite(count) || count < 0 || count != Math.rint(count))) {
            feature.suppressHistogram();
            return;
        }
        if (bins.stream().allMatch(count -> count == 0 || count >= AggregateDisclosure.MIN_COUNT)) return;
        if (bins.size() > 1 && (!(feature.getHistogramBinWidth() > 0)
                || !Double.isFinite(feature.getHistogramBinWidth()))) {
            feature.suppressHistogram();
            return;
        }

        List<Double> counts = new ArrayList<>();
        List<String> ranges = new ArrayList<>();
        double count = 0;
        double remaining = total;
        int first = 0;
        for (int index = 0; index < bins.size(); index++) {
            count += bins.get(index);
            remaining -= bins.get(index);
            boolean last = index == bins.size() - 1;
            if ((count > 0 && count < AggregateDisclosure.MIN_COUNT)
                    || (remaining > 0 && remaining < AggregateDisclosure.MIN_COUNT)) {
                continue;
            }
            double lower = feature.getMin() + first * feature.getHistogramBinWidth();
            double upper = last ? feature.getMax()
                    : feature.getMin() + (index + 1) * feature.getHistogramBinWidth();
            counts.add(count);
            ranges.add("[" + number(lower, feature.getHistogramBinWidth()) + " – "
                    + number(upper, feature.getHistogramBinWidth()) + (last ? "]" : ")"));
            first = index + 1;
            count = 0;
        }
        feature.coarsenHistogram(counts, ranges);
    }

    private static String number(double value, double width) {
        int decimals = Math.max(0, 2 - (int) Math.floor(Math.log10(width)));
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros();
        double magnitude = Math.abs(value);
        return value == 0 || (magnitude >= 0.0001 && magnitude < 1_000_000)
                ? rounded.toPlainString() : rounded.toString();
    }
}
