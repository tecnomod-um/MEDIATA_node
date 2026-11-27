package org.taniwha.privacy;

import org.junit.jupiter.api.Test;
import org.taniwha.statistics.DateFeatureStatistics;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class DateHistogramDisclosureTest {
    @Test void sparseCalendarDaysAreGroupedWithoutChangingAnyDescriptiveStatistics() {
        Map<String, Long> dates = new TreeMap<>();
        for (int day = 1; day <= 24; day++) dates.put(LocalDate.of(2020, 1, day).toString(), 1L);
        var feature = new DateFeatureStatistics("Visit", 24, 0, 0, "2020-01-01", "2020-01-24", dates, List.of(),
                "2020-01-12", 6.922186552431729, "2020-01-12", "2020-01-06", "2020-01-18");
        DateHistogramDisclosure.release(feature);
        assertThat(feature.getMean()).isEqualTo("2020-01-12");
        assertThat(feature.getMedian()).isEqualTo("2020-01-12");
        assertThat(feature.getQ1()).isEqualTo("2020-01-06");
        assertThat(feature.getQ3()).isEqualTo("2020-01-18");
        assertThat(feature.getDateHistogram()).isEmpty();
        assertThat(feature.getHistogramStatus()).isEqualTo("COARSENED");
        assertThat(feature.getDateBins()).hasSize(2).allSatisfy(bin -> assertThat(bin.count()).isGreaterThanOrEqualTo(6));
        assertThat(feature.getDateBins().get(0).start()).isEqualTo("2019-12-30");
        assertThat(feature.getDateBins().get(1).end()).isEqualTo("2020-01-26");
        assertThat(feature.getDateBins().stream().mapToLong(bin -> bin.count()).sum()).isEqualTo(24);
    }

    @Test void aSmallFinalWeekIsMergedRatherThanExposedByItsCountOrComplement() {
        var feature = new DateFeatureStatistics("Visit", 7, 0, 0, "2020-01-01", "2020-02-01",
                Map.of("2020-01-01", 6L, "2020-02-01", 1L), List.of(), "2020-01-05", 10, "2020-01-01", "2020-01-01", "2020-01-01");
        DateHistogramDisclosure.release(feature);
        assertThat(feature.getDateBins()).singleElement().satisfies(bin -> {
            assertThat(bin.count()).isEqualTo(7);
            assertThat(bin.end()).isEqualTo("2020-02-02");
        });
    }
}
