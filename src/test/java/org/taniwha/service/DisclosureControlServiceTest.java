package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.statistics.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class DisclosureControlServiceTest {

    private static final int MIN_SUBSET = 3;
    private static final int MIN_CELL = 2;

    private DisclosureControlService service;

    @BeforeEach
    void setUp() {
        service = new DisclosureControlService(MIN_SUBSET, MIN_CELL);
    }

    // -------------------------------------------------------------------------
    // Global suppression
    // -------------------------------------------------------------------------

    @Test
    void apply_totalRecordsBelowThreshold_suppressesAllFeatures() {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setContinuousFeatures(List.of(continuousFeature("age", 4)));
        response.setCategoricalFeatures(List.of(categoricalFeature("gender", Map.of("M", 2, "F", 2))));
        response.setDateFeatures(List.of(dateFeature("dob", 4)));
        response.setCovariances(Map.of("age", Map.of("age", 1.0)));

        int suppressed = service.apply(response, 2);

        assertThat(suppressed).isEqualTo(3);
        assertThat(response.getContinuousFeatures()).isEmpty();
        assertThat(response.getCategoricalFeatures()).isEmpty();
        assertThat(response.getDateFeatures()).isEmpty();
        assertThat(response.getOmittedFeatures()).hasSize(3);
        assertThat(response.getCovariances()).isEmpty();
        assertThat(response.getPearsonCorrelations()).isEmpty();
        assertThat(response.getSpearmanCorrelations()).isEmpty();
        assertThat(response.getChiSquareTest()).isEmpty();
    }

    @Test
    void apply_totalRecordsAtThreshold_doesNotGlobalSuppress() {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setContinuousFeatures(List.of(continuousFeature("age", 10)));

        service.apply(response, MIN_SUBSET);

        assertThat(response.getContinuousFeatures()).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Continuous feature suppression
    // -------------------------------------------------------------------------

    @Test
    void apply_continuousFeatureBelowSubsetThreshold_movedToOmitted() {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setContinuousFeatures(List.of(
                continuousFeature("weight", MIN_SUBSET - 1),
                continuousFeature("height", MIN_SUBSET + 1)
        ));

        service.apply(response, 20);

        assertThat(response.getContinuousFeatures())
                .extracting(fs -> fs.getFeatureName())
                .containsExactly("height");
        assertThat(response.getOmittedFeatures())
                .extracting(fs -> fs.getFeatureName())
                .containsExactly("weight");
    }

    @Test
    void apply_continuousOutliers_smallGroup_valueSuppressed() {
        // Only 1 outlier – below minCellCount (2) → values must be cleared
        List<Double> outliers = new ArrayList<>(List.of(250.0));
        ContinuousFeatureStatistics feature = new ContinuousFeatureStatistics(
                "bp", 20, 0, 0, 20, 60, 250, 90, 10, 80, 90, 100,
                List.of(1.0, 2.0), List.of("[60-80]", "[80-100]"), outliers);

        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setContinuousFeatures(List.of(feature));

        service.apply(response, 20);

        ContinuousFeatureStatistics result =
                (ContinuousFeatureStatistics) response.getContinuousFeatures().get(0);
        assertThat(result.getOutliers()).as("single outlier must be suppressed").isEmpty();
        // Aggregate stats are unchanged
        assertThat(result.getMin()).isEqualTo(60.0);
        assertThat(result.getMax()).isEqualTo(250.0);
    }

    @Test
    void apply_continuousOutliers_largeGroup_valuesPreserved() {
        // 3 outliers – >= minCellCount (2) → values must be kept
        List<Double> outliers = new ArrayList<>(List.of(200.0, 210.0, 220.0));
        ContinuousFeatureStatistics feature = new ContinuousFeatureStatistics(
                "bp", 20, 0, 0, 20, 60, 220, 90, 10, 80, 90, 100,
                List.of(1.0, 2.0), List.of("[60-80]", "[80-100]"), outliers);

        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setContinuousFeatures(List.of(feature));

        service.apply(response, 20);

        ContinuousFeatureStatistics result =
                (ContinuousFeatureStatistics) response.getContinuousFeatures().get(0);
        assertThat(result.getOutliers())
                .as("outlier group >= minCellCount must be preserved for analytics")
                .containsExactlyInAnyOrder(200.0, 210.0, 220.0);
    }

    @Test
    void apply_continuousNoOutliers_unchanged() {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setContinuousFeatures(List.of(continuousFeature("age", 20)));

        service.apply(response, 20);

        ContinuousFeatureStatistics result =
                (ContinuousFeatureStatistics) response.getContinuousFeatures().get(0);
        assertThat(result.getOutliers()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Categorical cell suppression
    // -------------------------------------------------------------------------

    @Test
    void apply_categoricalSmallCellsSuppressed() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("A", 10);
        counts.put("B", 1);  // below threshold (1 < 2)
        counts.put("C", 1);  // below threshold (1 < 2)

        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setCategoricalFeatures(List.of(categoricalFeature("diagnosis", counts)));

        service.apply(response, 20);

        assertThat(response.getCategoricalFeatures()).hasSize(1);
        CategoricalFeatureStatistics result =
                (CategoricalFeatureStatistics) response.getCategoricalFeatures().get(0);
        assertThat(result.getCategoryCounts()).containsOnlyKeys("A");
        assertThat(result.getMode()).isEqualTo("A");
    }

    @Test
    void apply_categoricalAllCellsSuppressed_movedToOmitted() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("A", 1);
        counts.put("B", 1);  // both 1 < minCellCount(2) → entire feature suppressed

        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setCategoricalFeatures(List.of(categoricalFeature("rare", counts)));

        service.apply(response, 20);

        assertThat(response.getCategoricalFeatures()).isEmpty();
        assertThat(response.getOmittedFeatures()).hasSize(1);
        assertThat(response.getOmittedFeatures().get(0).getFeatureName()).isEqualTo("rare");
    }

    @Test
    void apply_categoricalNoCellsSuppressed_unchanged() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("X", 10);
        counts.put("Y", 8);

        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setCategoricalFeatures(List.of(categoricalFeature("status", counts)));

        service.apply(response, 20);

        assertThat(response.getCategoricalFeatures()).hasSize(1);
        CategoricalFeatureStatistics result =
                (CategoricalFeatureStatistics) response.getCategoricalFeatures().get(0);
        assertThat(result.getCategoryCounts()).containsKeys("X", "Y");
    }

    // -------------------------------------------------------------------------
    // Date feature suppression
    // -------------------------------------------------------------------------

    @Test
    void apply_dateFeatureBelowSubsetThreshold_movedToOmitted() {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setDateFeatures(List.of(dateFeature("admission", MIN_SUBSET - 1)));

        service.apply(response, 20);

        assertThat(response.getDateFeatures()).isEmpty();
        assertThat(response.getOmittedFeatures()).hasSize(1);
        assertThat(response.getOmittedFeatures().get(0).getFeatureName()).isEqualTo("admission");
    }

    @Test
    void apply_dateHistogramSmallBucketsSuppressed() {
        Map<String, Long> histogram = new HashMap<>();
        histogram.put("2020-01-01", 10L);
        histogram.put("2020-01-02", 1L);  // below threshold

        DateFeatureStatistics feature = new DateFeatureStatistics(
                "visit", 20, 0, 0,
                "2020-01-01", "2020-01-02",
                histogram, List.of(),
                "2020-01-01", 1.0, "2020-01-01", "2020-01-01", "2020-01-02");

        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setDateFeatures(List.of(feature));

        service.apply(response, 20);

        assertThat(response.getDateFeatures()).hasSize(1);
        assertThat(response.getDateFeatures().get(0).getDateHistogram())
                .containsOnlyKeys("2020-01-01");
    }

    @Test
    void apply_dateOutliers_smallGroup_valuesSuppressed() {
        // Only 1 date outlier – below minCellCount → must be cleared
        DateFeatureStatistics feature = new DateFeatureStatistics(
                "dob", 20, 0, 0,
                "1900-01-01", "2024-01-01",
                new HashMap<>(), new ArrayList<>(List.of("1900-01-01")),
                "2000-01-01", 5.0, "2000-01-01", "1990-01-01", "2010-01-01");

        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setDateFeatures(List.of(feature));

        service.apply(response, 20);

        assertThat(response.getDateFeatures()).hasSize(1);
        assertThat(response.getDateFeatures().get(0).getOutliers())
                .as("single date outlier must be suppressed").isEmpty();
    }

    @Test
    void apply_dateOutliers_largeGroup_valuesPreserved() {
        // 2 date outliers – >= minCellCount → must be kept
        DateFeatureStatistics feature = new DateFeatureStatistics(
                "visit", 20, 0, 0,
                "1900-01-01", "2024-01-01",
                new HashMap<>(), new ArrayList<>(List.of("1900-01-01", "1901-03-15")),
                "2000-01-01", 5.0, "2000-01-01", "1990-01-01", "2010-01-01");

        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setDateFeatures(List.of(feature));

        service.apply(response, 20);

        assertThat(response.getDateFeatures()).hasSize(1);
        assertThat(response.getDateFeatures().get(0).getOutliers())
                .as("outlier group >= minCellCount must be preserved")
                .containsExactlyInAnyOrder("1900-01-01", "1901-03-15");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ContinuousFeatureStatistics continuousFeature(String name, long count) {
        return new ContinuousFeatureStatistics(
                name, count, 0, 0, (int) count,
                0, 100, 50, 10, 25, 50, 75,
                List.of(1.0), List.of("[0-100]"), List.of());
    }

    private CategoricalFeatureStatistics categoricalFeature(String name, Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> sorted = counts.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .toList();
        String mode = sorted.get(0).getKey();
        int modeFreq = sorted.get(0).getValue();
        String secondMode = sorted.size() > 1 ? sorted.get(1).getKey() : null;
        Integer secondModeFreq = sorted.size() > 1 ? sorted.get(1).getValue() : null;
        long total = counts.values().stream().mapToInt(Integer::intValue).sum();
        return new CategoricalFeatureStatistics(
                name, total, 0, 0, counts.size(),
                mode, modeFreq, (double) modeFreq / total * 100,
                secondMode, secondModeFreq,
                secondModeFreq != null ? (double) secondModeFreq / total * 100 : null,
                counts);
    }

    private DateFeatureStatistics dateFeature(String name, long count) {
        return new DateFeatureStatistics(
                name, count, 0, 0,
                "2020-01-01", "2024-01-01",
                new HashMap<>(), List.of(),
                "2022-01-01", 365.0, "2022-01-01", "2021-01-01", "2023-01-01");
    }

    // -------------------------------------------------------------------------
    // Correlation matrix cleanup for suppressed continuous features
    // -------------------------------------------------------------------------

    @Test
    void apply_continuousFeatureSuppressed_removedFromAllCorrelationMatrices() {
        // "bp" has count 2, below MIN_SUBSET (3) → suppressed
        // "age" has count 10 → kept
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setContinuousFeatures(List.of(
                continuousFeature("bp", MIN_SUBSET - 1),
                continuousFeature("age", 10)));

        // Simulate correlation matrices pre-populated by AggregateCalculator
        Map<String, Double> bpInner = new HashMap<>(Map.of("age", 0.9));
        Map<String, Double> ageInner = new HashMap<>(Map.of("bp", 0.9));
        response.setCovariances(new HashMap<>(Map.of("bp", bpInner, "age", ageInner)));
        response.setPearsonCorrelations(new HashMap<>(Map.of("bp", new HashMap<>(Map.of("age", 0.9)),
                "age", new HashMap<>(Map.of("bp", 0.9)))));
        response.setSpearmanCorrelations(new HashMap<>(Map.of("bp", new HashMap<>(Map.of("age", 0.8)),
                "age", new HashMap<>(Map.of("bp", 0.8)))));

        service.apply(response, 20);

        assertThat(response.getContinuousFeatures())
                .extracting(fs -> fs.getFeatureName()).containsExactly("age");
        assertThat(response.getOmittedFeatures())
                .extracting(fs -> fs.getFeatureName()).containsExactly("bp");

        // "bp" must be gone as outer key
        assertThat(response.getCovariances()).doesNotContainKey("bp");
        assertThat(response.getPearsonCorrelations()).doesNotContainKey("bp");
        assertThat(response.getSpearmanCorrelations()).doesNotContainKey("bp");

        // "bp" must also be removed from "age"'s inner map
        assertThat(response.getCovariances().get("age")).doesNotContainKey("bp");
        assertThat(response.getPearsonCorrelations().get("age")).doesNotContainKey("bp");
        assertThat(response.getSpearmanCorrelations().get("age")).doesNotContainKey("bp");
    }

    // -------------------------------------------------------------------------
    // Chi-squared cleanup for fully-suppressed categorical features
    // -------------------------------------------------------------------------

    @Test
    void apply_categoricalFeatureFullySuppressed_removedFromChiSquaredResults() {
        // "rare" has all cells below MIN_CELL → fully suppressed
        // "status" is fine
        Map<String, Integer> rareCounts = new HashMap<>(Map.of("A", 1));   // 1 < 2 → suppressed
        Map<String, Integer> statusCounts = new HashMap<>(Map.of("X", 10, "Y", 8));

        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setCategoricalFeatures(List.of(
                categoricalFeature("rare", rareCounts),
                categoricalFeature("status", statusCounts)));

        // Simulate chi-squared results pre-populated by AggregateCalculator
        List<org.taniwha.statistics.ChiSquaredTestResult> chi = new ArrayList<>();
        chi.add(new org.taniwha.statistics.ChiSquaredTestResult("rare", "status", 0.04));
        chi.add(new org.taniwha.statistics.ChiSquaredTestResult("status", "other", 0.12));
        response.setChiSquareTest(chi);

        service.apply(response, 20);

        assertThat(response.getCategoricalFeatures())
                .extracting(fs -> fs.getFeatureName()).containsExactly("status");
        assertThat(response.getOmittedFeatures())
                .extracting(fs -> fs.getFeatureName()).containsExactly("rare");

        // The chi-squared entry involving "rare" must be removed
        assertThat(response.getChiSquareTest())
                .noneMatch(r -> "rare".equals(r.getCategory1()) || "rare".equals(r.getCategory2()));
        // Unrelated chi-squared entries must be preserved
        assertThat(response.getChiSquareTest())
                .anySatisfy(r -> {
                    assertThat(r.getCategory1()).isEqualTo("status");
                    assertThat(r.getCategory2()).isEqualTo("other");
                });
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    @Test
    void getMinSubsetSize_returnsConfiguredValue() {
        assertThat(service.getMinSubsetSize()).isEqualTo(MIN_SUBSET);
    }

    @Test
    void getMinCellCount_returnsConfiguredValue() {
        assertThat(service.getMinCellCount()).isEqualTo(MIN_CELL);
    }

    // -------------------------------------------------------------------------
    // Null-list guards in moveFeaturesToOmitted / moveDateFeaturesToOmitted
    // -------------------------------------------------------------------------

    @Test
    void apply_totalRecordsBelowThreshold_nullFeatureLists_doesNotThrow() {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setContinuousFeatures(null);
        response.setCategoricalFeatures(null);
        response.setDateFeatures(null);

        int suppressed = service.apply(response, 2);

        assertThat(suppressed).isZero();
        assertThat(response.getOmittedFeatures()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // rebuildCategoricalFeature with only one surviving category (no secondMode)
    // -------------------------------------------------------------------------

    @Test
    void apply_categoricalSingleSurvivorAfterCellSuppression_rebuildWithNoSecondMode() {
        // "A"=10 survives, "B"=1 is suppressed → only one category left
        Map<String, Integer> counts = new HashMap<>();
        counts.put("A", 10);
        counts.put("B", 1);

        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setCategoricalFeatures(List.of(categoricalFeature("dx", counts)));

        service.apply(response, 20);

        assertThat(response.getCategoricalFeatures()).hasSize(1);
        CategoricalFeatureStatistics result =
                (CategoricalFeatureStatistics) response.getCategoricalFeatures().get(0);
        assertThat(result.getMode()).isEqualTo("A");
        assertThat(result.getSecondMode()).isNull();
        assertThat(result.getSecondModeFrequency()).isNull();
        assertThat(result.getSecondModePercentage()).isNull();
    }

    // -------------------------------------------------------------------------
    // Null-matrix guard in removeFromCorrelationMatrix
    // -------------------------------------------------------------------------

    @Test
    void apply_continuousFeatureSuppressed_nullCorrelationMatrix_doesNotThrow() {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setContinuousFeatures(List.of(continuousFeature("tiny", MIN_SUBSET - 1)));
        response.setCovariances(null);
        response.setPearsonCorrelations(null);
        response.setSpearmanCorrelations(null);

        int suppressed = service.apply(response, 20);

        assertThat(suppressed).isEqualTo(1);
        assertThat(response.getOmittedFeatures()).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // safeList null-guard
    // -------------------------------------------------------------------------

    @Test
    void apply_nullOmittedFeaturesOnInput_treatedAsEmpty() {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        response.setOmittedFeatures(null);
        response.setContinuousFeatures(List.of(continuousFeature("f", MIN_SUBSET - 1)));

        // should not throw; omittedFeatures must be populated after suppression
        service.apply(response, 20);

        assertThat(response.getOmittedFeatures()).hasSize(1);
    }
}
