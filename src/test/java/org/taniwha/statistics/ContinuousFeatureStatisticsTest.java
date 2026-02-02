package org.taniwha.statistics;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ContinuousFeatureStatisticsTest {

    @Test
    void constructor_withAllParameters_shouldSetFields() {
        List<Double> histogram = Arrays.asList(10.0, 20.0, 30.0);
        List<String> binRanges = Arrays.asList("0-10", "10-20", "20-30");
        List<Double> outliers = Arrays.asList(100.0, 105.0);

        ContinuousFeatureStatistics stats = new ContinuousFeatureStatistics(
                "temperature",
                1000,
                5.5,
                55,
                50,
                -10.0,
                40.0,
                20.5,
                8.3,
                15.0,
                20.0,
                25.0,
                histogram,
                binRanges,
                outliers
        );

        assertThat(stats.getFeatureName()).isEqualTo("temperature");
        assertThat(stats.getCount()).isEqualTo(1000);
        assertThat(stats.getPercentMissing()).isEqualTo(5.5);
        assertThat(stats.getMissingValuesCount()).isEqualTo(55);
        assertThat(stats.getCardinality()).isEqualTo(50);
        assertThat(stats.getMin()).isEqualTo(-10.0);
        assertThat(stats.getMax()).isEqualTo(40.0);
        assertThat(stats.getMean()).isEqualTo(20.5);
        assertThat(stats.getStdDev()).isEqualTo(8.3);
        assertThat(stats.getQrt1()).isEqualTo(15.0);
        assertThat(stats.getMedian()).isEqualTo(20.0);
        assertThat(stats.getQrt3()).isEqualTo(25.0);
        assertThat(stats.getHistogram()).isEqualTo(histogram);
        assertThat(stats.getBinRanges()).isEqualTo(binRanges);
        assertThat(stats.getOutliers()).isEqualTo(outliers);
    }

    @Test
    void constructor_withEmptyCollections_shouldWork() {
        ContinuousFeatureStatistics stats = new ContinuousFeatureStatistics(
                "age",
                500,
                0.0,
                0,
                100,
                0.0,
                100.0,
                50.0,
                15.0,
                35.0,
                50.0,
                65.0,
                Arrays.asList(),
                Arrays.asList(),
                Arrays.asList()
        );

        assertThat(stats.getHistogram()).isEmpty();
        assertThat(stats.getBinRanges()).isEmpty();
        assertThat(stats.getOutliers()).isEmpty();
    }

    @Test
    void getters_shouldReturnCorrectValues() {
        ContinuousFeatureStatistics stats = new ContinuousFeatureStatistics(
                "weight",
                200,
                10.0,
                20,
                75,
                50.0,
                120.0,
                85.0,
                12.5,
                75.0,
                85.0,
                95.0,
                Arrays.asList(5.0, 10.0, 15.0),
                Arrays.asList("50-70", "70-90", "90-110"),
                Arrays.asList(150.0)
        );

        assertThat(stats.getFeatureName()).isEqualTo("weight");
        assertThat(stats.getCardinality()).isEqualTo(75);
        assertThat(stats.getMin()).isEqualTo(50.0);
        assertThat(stats.getMax()).isEqualTo(120.0);
        assertThat(stats.getMean()).isEqualTo(85.0);
        assertThat(stats.getStdDev()).isEqualTo(12.5);
        assertThat(stats.getQrt1()).isEqualTo(75.0);
        assertThat(stats.getMedian()).isEqualTo(85.0);
        assertThat(stats.getQrt3()).isEqualTo(95.0);
    }

    @Test
    void extremeValues_shouldBeHandled() {
        ContinuousFeatureStatistics stats = new ContinuousFeatureStatistics(
                "extreme",
                10,
                0.0,
                0,
                10,
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                0.0,
                Double.MAX_VALUE / 2,
                -1000.0,
                0.0,
                1000.0,
                Arrays.asList(1.0),
                Arrays.asList("min-max"),
                Arrays.asList(Double.MAX_VALUE)
        );

        assertThat(stats.getMin()).isEqualTo(Double.MIN_VALUE);
        assertThat(stats.getMax()).isEqualTo(Double.MAX_VALUE);
        assertThat(stats.getStdDev()).isEqualTo(Double.MAX_VALUE / 2);
    }
}
