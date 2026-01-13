package org.taniwha.statistics;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DateFeatureStatisticsTest {

    @Test
    void constructor_withAllParameters_shouldSetFields() {
        Map<String, Long> dateHistogram = new HashMap<>();
        dateHistogram.put("2020-01", 10L);
        dateHistogram.put("2020-02", 20L);

        List<String> outliers = Arrays.asList("2020-01-01", "2020-12-31");

        DateFeatureStatistics stats = new DateFeatureStatistics(
                "timestamp",
                1000,
                2.5,
                25,
                "2020-01-01",
                "2020-12-31",
                dateHistogram,
                outliers,
                "2020-06-15",
                45.0,
                "2020-06-01",
                "2020-04-01",
                "2020-09-01"
        );

        assertThat(stats.getFeatureName()).isEqualTo("timestamp");
        assertThat(stats.getCount()).isEqualTo(1000);
        assertThat(stats.getPercentMissing()).isEqualTo(2.5);
        assertThat(stats.getMissingValuesCount()).isEqualTo(25);
        assertThat(stats.getEarliestDate()).isEqualTo("2020-01-01");
        assertThat(stats.getLatestDate()).isEqualTo("2020-12-31");
        assertThat(stats.getDateHistogram()).isEqualTo(dateHistogram);
        assertThat(stats.getOutliers()).isEqualTo(outliers);
        assertThat(stats.getMean()).isEqualTo("2020-06-15");
        assertThat(stats.getStdDev()).isEqualTo(45.0);
        assertThat(stats.getMedian()).isEqualTo("2020-06-01");
        assertThat(stats.getQ1()).isEqualTo("2020-04-01");
        assertThat(stats.getQ3()).isEqualTo("2020-09-01");
    }

    @Test
    void constructor_withEmptyCollections_shouldWork() {
        DateFeatureStatistics stats = new DateFeatureStatistics(
                "date",
                100,
                0.0,
                0,
                "2021-01-01",
                "2021-06-30",
                new HashMap<>(),
                Arrays.asList(),
                "2021-03-15",
                30.0,
                "2021-03-01",
                "2021-02-01",
                "2021-04-01"
        );

        assertThat(stats.getDateHistogram()).isEmpty();
        assertThat(stats.getOutliers()).isEmpty();
    }

    @Test
    void getters_shouldReturnCorrectValues() {
        Map<String, Long> dateHistogram = new HashMap<>();
        dateHistogram.put("Q1", 50L);
        dateHistogram.put("Q2", 100L);

        DateFeatureStatistics stats = new DateFeatureStatistics(
                "event_date",
                500,
                5.0,
                25,
                "2022-01-01",
                "2022-12-31",
                dateHistogram,
                Arrays.asList("2022-01-05"),
                "2022-06-15",
                90.0,
                "2022-06-01",
                "2022-03-01",
                "2022-09-01"
        );

        assertThat(stats.getFeatureName()).isEqualTo("event_date");
        assertThat(stats.getEarliestDate()).isEqualTo("2022-01-01");
        assertThat(stats.getLatestDate()).isEqualTo("2022-12-31");
        assertThat(stats.getDateHistogram()).hasSize(2);
        assertThat(stats.getOutliers()).hasSize(1);
    }

    @Test
    void dateFormats_shouldBePreserved() {
        DateFeatureStatistics stats = new DateFeatureStatistics(
                "created_at",
                200,
                1.0,
                2,
                "2023-01-01T00:00:00",
                "2023-06-30T23:59:59",
                new HashMap<>(),
                Arrays.asList(),
                "2023-03-15T12:00:00",
                50.0,
                "2023-03-01T00:00:00",
                "2023-02-01T00:00:00",
                "2023-04-01T00:00:00"
        );

        assertThat(stats.getEarliestDate()).isEqualTo("2023-01-01T00:00:00");
        assertThat(stats.getLatestDate()).isEqualTo("2023-06-30T23:59:59");
        assertThat(stats.getMean()).isEqualTo("2023-03-15T12:00:00");
    }
}
