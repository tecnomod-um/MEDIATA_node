package org.taniwha.statistics;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CategoricalFeatureStatisticsTest {

    @Test
    void constructor_withAllParameters_shouldSetFields() {
        Map<String, Integer> categoryCounts = new HashMap<>();
        categoryCounts.put("red", 50);
        categoryCounts.put("blue", 30);
        categoryCounts.put("green", 20);

        CategoricalFeatureStatistics stats = new CategoricalFeatureStatistics(
                "color",
                1000,
                5.0,
                50,
                3,
                "red",
                50,
                50.0,
                "blue",
                30,
                30.0,
                categoryCounts
        );

        assertThat(stats.getFeatureName()).isEqualTo("color");
        assertThat(stats.getCount()).isEqualTo(1000);
        assertThat(stats.getPercentMissing()).isEqualTo(5.0);
        assertThat(stats.getMissingValuesCount()).isEqualTo(50);
        assertThat(stats.getCardinality()).isEqualTo(3);
        assertThat(stats.getMode()).isEqualTo("red");
        assertThat(stats.getModeFrequency()).isEqualTo(50);
        assertThat(stats.getModeFrequencyPercentage()).isEqualTo(50.0);
        assertThat(stats.getSecondMode()).isEqualTo("blue");
        assertThat(stats.getSecondModeFrequency()).isEqualTo(30);
        assertThat(stats.getSecondModePercentage()).isEqualTo(30.0);
        assertThat(stats.getCategoryCounts()).isEqualTo(categoryCounts);
    }

    @Test
    void constructor_withNullSecondMode_shouldWork() {
        Map<String, Integer> categoryCounts = new HashMap<>();
        categoryCounts.put("only", 100);

        CategoricalFeatureStatistics stats = new CategoricalFeatureStatistics(
                "single",
                100,
                0.0,
                0,
                1,
                "only",
                100,
                100.0,
                null,
                null,
                null,
                categoryCounts
        );

        assertThat(stats.getSecondMode()).isNull();
        assertThat(stats.getSecondModeFrequency()).isNull();
        assertThat(stats.getSecondModePercentage()).isNull();
    }

    @Test
    void getters_shouldReturnCorrectValues() {
        Map<String, Integer> categoryCounts = new HashMap<>();
        categoryCounts.put("A", 100);
        categoryCounts.put("B", 200);

        CategoricalFeatureStatistics stats = new CategoricalFeatureStatistics(
                "grade",
                300,
                0.0,
                0,
                2,
                "B",
                200,
                66.67,
                "A",
                100,
                33.33,
                categoryCounts
        );

        assertThat(stats.getFeatureName()).isEqualTo("grade");
        assertThat(stats.getCardinality()).isEqualTo(2);
        assertThat(stats.getCategoryCounts()).containsEntry("A", 100);
        assertThat(stats.getCategoryCounts()).containsEntry("B", 200);
        assertThat(stats.getMode()).isEqualTo("B");
        assertThat(stats.getModeFrequency()).isEqualTo(200);
    }

    @Test
    void largeCardinality_shouldBeHandled() {
        Map<String, Integer> categoryCounts = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            categoryCounts.put("cat" + i, i);
        }

        CategoricalFeatureStatistics stats = new CategoricalFeatureStatistics(
                "many_categories",
                10000,
                0.0,
                0,
                1000,
                "cat999",
                999,
                9.99,
                "cat998",
                998,
                9.98,
                categoryCounts
        );

        assertThat(stats.getCardinality()).isEqualTo(1000);
        assertThat(stats.getCategoryCounts()).hasSize(1000);
    }
}
