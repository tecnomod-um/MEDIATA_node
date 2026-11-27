package org.taniwha.privacy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.taniwha.security.FileFilter;
import org.taniwha.service.AnalyticsService;
import org.taniwha.service.DataProcessingService;
import org.taniwha.service.FileService;
import org.taniwha.service.jobs.AnalyticsProcessingJobs;
import org.taniwha.statistics.*;

import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WholeDatasetProfilingTest {
    @TempDir Path base;
    private AnalyticsService analytics;

    private void open(String data) throws Exception {
        Path path = base.resolve("datasets/source.csv");
        Files.createDirectories(path.getParent());
        Files.writeString(path, data);
        FileService files = mock(FileService.class);
        when(files.getDatasetFilePath("source.csv")).thenReturn(path.toString());
        analytics = new AnalyticsService(new DataProcessingService(new FileFilter()), files, new AnalyticsProcessingJobs(),
                new DiscoveryDisclosureService(new DiscoveryQueryLedger(base.toString())));
    }

    @AfterEach void close() { if (analytics != null) analytics.shutdown(); }

    @Test void wholeFilesRetainMissingCountsWhileWithholdingSparseValues() throws Exception {
        open("Age,Group,Score,Empty\n20,A,1,\n30,A,2,\n40,B,,\n50,C,,\n");
        var result = analytics.processSingleFileOnDisk("source.csv");
        assertThat(result.getDisclosureMode()).isEqualTo("WHOLE_DATASET");
        assertThat(result.isDisclosureWithheld()).isFalse();
        assertThat(result.getContinuousFeatures()).isEmpty();
        assertThat(result.getOmittedFeatures()).anySatisfy(feature -> {
            assertThat(feature.getFeatureName()).isEqualTo("Score");
            assertThat(feature.getCount()).isEqualTo(2);
            assertThat(feature.getMissingValuesCount()).isEqualTo(2);
            assertThat(feature.getPercentMissing()).isEqualTo(50);
            assertThat(feature.getType()).isEqualTo("continuous");
            assertThat(feature.isCountsReleased()).isTrue();
        });
        assertThat(result.getOmittedFeatures()).anySatisfy(feature -> {
            assertThat(feature.getFeatureName()).isEqualTo("Group");
            assertThat(feature.getCount()).isEqualTo(4);
            assertThat(feature.isCountsReleased()).isTrue();
        });
        assertThat(result.getCategoricalFeatures()).singleElement().satisfies(feature -> {
            assertThat(feature.getFeatureName()).isEqualTo("Empty");
            assertThat(feature.getCount()).isZero();
            assertThat(feature.getMissingValuesCount()).isEqualTo(4);
            assertThat(feature.getPercentMissing()).isEqualTo(100);
        });
        assertThat(analytics.filterDataByName("source.csv", Map.of("operator", "AND", "conditions", Map.of())))
                .usingRecursiveComparison().withEqualsForType(Double::equals, Double.class).isEqualTo(result);
        assertThat(analytics.filterDataByName("source.csv", Map.of("operator", "OR", "conditions", Map.of())))
                .usingRecursiveComparison().withEqualsForType(Double::equals, Double.class).isEqualTo(result);
    }

    @Test void emptyColumnsKeepTheirSelectedTypesWithoutInventingNumericalValues() throws Exception {
        open("Age,EmptyNumber,EmptyCategory\n20,,\n30,,\n");
        var result = analytics.filterDataByName("source.csv", null, Map.of("EmptyNumber", "continuous", "EmptyCategory", "categorical"));
        assertThat(result.getContinuousFeatures()).anySatisfy(feature -> {
            assertThat(feature.getFeatureName()).isEqualTo("EmptyNumber");
            assertThat(feature.getMissingValuesCount()).isEqualTo(2);
            assertThat(((ContinuousFeatureStatistics) feature).getMean()).isNull();
            assertThat(((ContinuousFeatureStatistics) feature).getMin()).isNull();
        });
        assertThat(result.getCategoricalFeatures()).singleElement().satisfies(feature -> {
            assertThat(feature.getMissingValuesCount()).isEqualTo(2);
            assertThat(feature.getPercentMissing()).isEqualTo(100);
            assertThat(((CategoricalFeatureStatistics) feature).getCategoryCounts()).isEmpty();
        });
    }

    @Test void missingPlaceholdersAndInvalidValuesDoNotResetPreviouslyCollectedObservations() throws Exception {
        open("Age,Visit\n-,2020-01-01\n20,invalid\n30,2020-01-01\n-,\n40,2020-01-01\n50,2020-01-01\n60,2020-01-01\n70,2020-01-01\n");
        var result = analytics.processSingleFileOnDisk("source.csv");
        assertThat(result.getContinuousFeatures()).singleElement().satisfies(feature -> {
            assertThat(feature.getCount()).isEqualTo(6);
            assertThat(feature.getMissingValuesCount()).isEqualTo(2);
            assertThat(((ContinuousFeatureStatistics) feature).getMean()).isEqualTo(45);
        });
        assertThat(result.getDateFeatures()).singleElement().satisfies(feature -> {
            assertThat(feature.getCount()).isEqualTo(6);
            assertThat(feature.getMissingValuesCount()).isEqualTo(2);
        });
    }

    @Test void wholeFilesDoNotDependOnQueryHistoryAndFilteredSelectionsStillDo() throws Exception {
        open("Age,Group\n" + "20,A\n".repeat(6) + "30,B\n".repeat(6));
        Files.writeString(base.resolve("discovery-privacy/registry.json"), "unavailable history");
        var result = analytics.processSingleFileOnDisk("source.csv");
        assertThat(result.getContinuousFeatures()).hasSize(1);
        assertThat(result.getCategoricalFeatures()).hasSize(1);
        var filtered = analytics.filterDataByName("source.csv", Map.of("operator", "AND", "conditions", Map.of("Age", Map.of(
                "conditions", List.of(Map.of("type", "less", "filterType", "continuous", "value", 25)), "operators", List.of()))));
        assertThat(filtered.isDisclosureWithheld()).isTrue();
        assertThat(filtered.getContinuousFeatures()).isEmpty();
    }

    @Test void wholeFileMomentsRemainVisibleButRareModalValuesStayProtected() throws Exception {
        StringBuilder csv = new StringBuilder("Score\n");
        for (int value = 0; value < 210; value++) csv.append(value).append('\n').append(value).append('\n');
        open(csv.toString());
        var result = analytics.processSingleFileOnDisk("source.csv");
        assertThat(result.getContinuousFeatures()).singleElement().satisfies(feature -> {
            var numeric = (ContinuousFeatureStatistics) feature;
            assertThat(numeric.getMean()).isEqualTo(104.5);
            assertThat(numeric.getModes()).isEmpty();
            assertThat(numeric.getModeFrequency()).isNull();
            assertThat(numeric.getModeStatus()).startsWith("Withheld:");
        });
    }

    @Test void ordinaryMarginalsRemainVisibleButSmallJointCategoryCountsDoNot() throws Exception {
        open("Group,Arm\n" + "A,U\n" + "A,V\n".repeat(11) + "B,U\n".repeat(11) + "B,V\n");
        var result = analytics.processSingleFileOnDisk("source.csv");
        assertThat(result.getCategoricalFeatures()).hasSize(2).allSatisfy(feature -> {
            assertThat(((CategoricalFeatureStatistics) feature).getCategoryCounts().values()).containsExactly(12, 12);
        });
        assertThat(result.getOmittedFeatures()).isEmpty();
        assertThat(result.getCategoricalPairSummaries()).isEmpty();
        assertThat(result.getChiSquareTest()).isEmpty();
    }

    @Test void binaryRelationshipsCannotRecoverSmallCellsFromCrossProducts() throws Exception {
        open("X,Y\n" + "0,0\n" + "0,1\n".repeat(11) + "1,0\n".repeat(11) + "1,1\n");
        var result = analytics.processSingleFileOnDisk("source.csv");
        assertThat(result.getContinuousFeatures()).hasSize(2);
        assertThat(result.getCovariances()).isEmpty();
        assertThat(result.getPearsonCorrelations()).isEmpty();
        assertThat(result.getSpearmanCorrelations()).isEmpty();
        assertThat(result.getNumericPairSummaries()).noneMatch(pair -> !pair.featureX().equals(pair.featureY()));
    }

    @Test void rareBinaryValuesRemainProtectedAfterChangingInterpretation() throws Exception {
        open("Flag\n1\n" + "0\n".repeat(23));
        for (var types : List.of(Map.of("Flag", "continuous"), Map.of("Flag", "categorical"))) {
            var result = analytics.filterDataByName("source.csv", null, types);
            assertThat(result.getContinuousFeatures()).isEmpty();
            assertThat(result.getCategoricalFeatures()).isEmpty();
            assertThat(result.getOmittedFeatures()).singleElement().satisfies(feature -> {
                assertThat(feature.getCount()).isEqualTo(24);
                assertThat(feature.getMissingValuesCount()).isZero();
                assertThat(feature.isCountsReleased()).isTrue();
            });
            assertThat(result.getNumericPairSummaries()).isEmpty();
        }
    }
}
