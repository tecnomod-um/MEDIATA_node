package org.taniwha.privacy;

import org.junit.jupiter.api.Test;
import org.taniwha.statistics.ContinuousFeatureStatistics;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class HistogramDisclosureTest {
    private ContinuousFeatureStatistics feature(List<Double> counts) {
        long count = counts.stream().mapToLong(Double::longValue).sum();
        var feature = new ContinuousFeatureStatistics("Age", count, 0, 0, 10,
                0, counts.size() * 10, 20, 1, 10, 20, 30, counts,
                IntStream.range(0, counts.size()).mapToObj(i -> "range " + i).toList(), List.of());
        feature.setHistogramBinWidth(10);
        return feature;
    }

    @Test void combinesSparseTailsWithoutLosingObservationsOrInventingBins() {
        var feature = feature(List.of(1.0, 10.0, 12.0, 1.0));
        HistogramDisclosure.release(feature);
        assertThat(feature.getHistogram()).containsExactly(11.0, 13.0);
        assertThat(feature.getBinRanges()).containsExactly("[0 – 20)", "[20 – 40]");
        assertThat(feature.getHistogramStatus()).isEqualTo("COARSENED");
        assertThat(feature.getHistogram().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(feature.getCount());
    }

    @Test void preservesSafeBinsAndEmptyGaps() {
        var feature = feature(List.of(0.0, 6.0, 0.0, 6.0));
        HistogramDisclosure.release(feature);
        assertThat(feature.getHistogram()).containsExactly(0.0, 6.0, 0.0, 6.0);
        assertThat(feature.getHistogramStatus()).isEqualTo("AVAILABLE");
    }

    @Test void aSparseDistributionStillHasACorrectCountedRange() {
        var feature = feature(List.of(1.0, 2.0, 3.0));
        HistogramDisclosure.release(feature);
        assertThat(feature.getHistogram()).containsExactly(6.0);
        assertThat(feature.getBinRanges()).containsExactly("[0 – 30]");
    }

    @Test void malformedCountsAndTinyFeaturesAreNotPlottedAsZero() {
        for (var counts : List.of(List.of(1.0, 2.0), List.of(-1.0, 9.0), List.of(6.5, 8.0))) {
            var feature = feature(counts);
            HistogramDisclosure.release(feature);
            assertThat(feature.getHistogram()).isEmpty();
            assertThat(feature.getBinRanges()).isEmpty();
            assertThat(feature.getHistogramStatus()).isEqualTo("WITHHELD");
        }
    }
}
