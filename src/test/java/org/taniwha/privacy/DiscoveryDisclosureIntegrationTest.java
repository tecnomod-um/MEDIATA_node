package org.taniwha.privacy;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.taniwha.security.FileFilter;
import org.taniwha.service.*;
import org.taniwha.service.jobs.AnalyticsProcessingJobs;
import org.taniwha.dto.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscoveryDisclosureIntegrationTest {
    @TempDir Path base;
    AnalyticsService analytics;
    AnalyticsProcessingJobs jobs;
    @AfterEach void close() { if (analytics != null) analytics.shutdown(); }
    private void initialise(String csv) throws Exception {
        initialise(csv, true);
    }
    private void initialise(String csv, boolean historyEnabled) throws Exception {
        Path path = base.resolve("datasets/cohort.csv"); Files.createDirectories(path.getParent()); Files.writeString(path, csv);
        FileService files = mock(FileService.class); when(files.getDatasetFilePath("cohort.csv")).thenReturn(path.toString());
        jobs = new AnalyticsProcessingJobs();
        analytics = new AnalyticsService(new DataProcessingService(new FileFilter()), files, jobs,
                new DiscoveryDisclosureService(new DiscoveryQueryLedger(base.toString(), historyEnabled)));
    }
    private Map<String, Object> less(int value) {
        return Map.of("operator", "AND", "conditions", Map.of("Age", Map.of("conditions", List.of(Map.of("filterType", "continuous", "type", "less", "value", value)), "operators", List.of())));
    }
    private String csv() {
        StringBuilder csv = new StringBuilder("Age,Band\n");
        for (int i = 0; i < 24; i++) csv.append(20 + i).append(',').append(i < 12 ? "A" : "B").append('\n');
        return csv.toString();
    }
    private void blocked(AnalyticsResponseDTO response) {
        assertThat(response.getMessage()).isEqualTo(DiscoveryQueryLedger.BLOCKED);
        assertThat(response.getContinuousFeatures()).isEmpty(); assertThat(response.getCategoricalFeatures()).isEmpty();
        assertThat(response.getNumericPairSummaries()).isEmpty(); assertThat(response.getCovariances()).isEmpty();
        assertThat(response.getPearsonCorrelations()).isEmpty(); assertThat(response.getSpearmanCorrelations()).isEmpty();
        assertThat(response.getOmittedFeatures()).isEmpty();
    }
    @Test void overlappingFiltersCannotRecoverOnePatientFromCountsMeansVariancesOrRelationships() throws Exception {
        initialise(csv());
        var first = analytics.filterDataByName("cohort.csv", less(32));
        assertThat(first.getContinuousFeatures()).anySatisfy(feature -> assertThat(feature.getCount()).isEqualTo(12));
        blocked(analytics.filterDataByName("cohort.csv", less(31)));
        blocked(analytics.filterDataByName("cohort.csv", less(43)));
    }

    @Test void disabledHistoryAllowsIndependentFiltersAndDatasetUpdatesWithoutTouchingTheLedger() throws Exception {
        Path registry = base.resolve("discovery-privacy/registry.json");
        Files.createDirectories(registry.getParent());
        Files.writeString(registry, "invalid ledger");
        initialise(csv(), false);

        assertThat(analytics.filterDataByName("cohort.csv", less(32)).getContinuousFeatures())
                .anySatisfy(feature -> assertThat(feature.getCount()).isEqualTo(12));
        Files.writeString(base.resolve("datasets/cohort.csv"), csv().replace("20,A", "21,A"));
        var filtered = analytics.filterDataByName("cohort.csv", less(31));
        assertThat(filtered.getContinuousFeatures())
                .anySatisfy(feature -> assertThat(feature.getCount()).isEqualTo(11));
        assertThat(filtered.getDisclosurePolicyVersion()).isEqualTo(2);
        blocked(analytics.filterDataByName("cohort.csv", less(25)));
        assertThat(Files.readString(registry)).isEqualTo("invalid ledger");
    }

    @Test void reportedQuantilesAndCategoryPercentagesUseTheObservedCohortConsistently() throws Exception {
        StringBuilder data = new StringBuilder("Age,Sex,VisitDate\n");
        for (int value = 1; value <= 4; value++) for (int repeat = 0; repeat < 6; repeat++)
            data.append(value).append(value <= 2 ? ",F,2020-01-01\n" : ",M,2020-01-05\n");
        for (int i = 0; i < 6; i++) data.append(",,\n");
        initialise(data.toString());
        var response = analytics.filterDataByName("cohort.csv", null);
        assertThat(response.getDisclosurePolicyVersion()).isEqualTo(2);
        assertThat(response.getStatisticsConventionVersion()).isEqualTo(1);
        assertThat(response.getContinuousFeatures()).singleElement().satisfies(feature -> {
            var numeric = (org.taniwha.statistics.ContinuousFeatureStatistics) feature;
            assertThat(numeric.getCount()).isEqualTo(24);
            assertThat(numeric.getMissingValuesCount()).isEqualTo(6);
            assertThat(numeric.getMedian()).isEqualTo(2.5);
            assertThat(numeric.getQrt1()).isEqualTo(1.75);
            assertThat(numeric.getQrt3()).isEqualTo(3.25);
            assertThat(numeric.getSumCubedDeviations()).isZero();
            assertThat(numeric.getSkewness()).isZero();
            assertThat(numeric.getKurtosis()).isNotNull();
            assertThat(numeric.getSumFourthDeviations()).isPositive();
            assertThat(numeric.getSampleStdDev()).isGreaterThan(numeric.getStdDev());
            assertThat(numeric.getMeanCiLower()).isLessThan(2.5);
            assertThat(numeric.getMeanCiUpper()).isGreaterThan(2.5);
            assertThat(numeric.getModes()).containsExactly(1., 2., 3., 4.);
            assertThat(numeric.getModeFrequency()).isEqualTo(6);
        });
        assertThat(response.getCategoricalFeatures()).singleElement().satisfies(feature -> {
            var categorical = (org.taniwha.statistics.CategoricalFeatureStatistics) feature;
            assertThat(categorical.getModeFrequencyPercentage()).isEqualTo(50);
            assertThat(categorical.getCardinality()).isEqualTo(2);
            assertThat(categorical.getMode()).isEqualTo("F");
            assertThat(categorical.getSecondMode()).isEqualTo("M");
            assertThat(categorical.getSecondModePercentage()).isEqualTo(50);
            assertThat(categorical.getPercentMissing()).isEqualTo(20);
        });
        assertThat(response.getDateFeatures()).singleElement().satisfies(date ->
                assertThat(date.getMedian()).isEqualTo("2020-01-03"));
    }

    @Test void addedNumericalStatisticsFollowFilteringAndTypeChanges() throws Exception {
        StringBuilder data = new StringBuilder("Age\n");
        for (int value = 1; value <= 4; value++) data.append((value + "\n").repeat(6));
        data.append("\n".repeat(6));
        initialise(data.toString());
        var filtered = analytics.filterDataByName("cohort.csv", less(3));
        assertThat(filtered.getContinuousFeatures()).singleElement().satisfies(feature -> {
            var numeric = (org.taniwha.statistics.ContinuousFeatureStatistics) feature;
            assertThat(numeric.getCount()).isEqualTo(12);
            assertThat(numeric.getMean()).isEqualTo(1.5);
            assertThat(numeric.getSkewness()).isZero();
            assertThat(numeric.getModes()).containsExactly(1., 2.);
            assertThat(numeric.getModeFrequency()).isEqualTo(6);
        });
        var categorical = analytics.filterDataByName("cohort.csv", null, Map.of("Age", "categorical"));
        assertThat(categorical.getContinuousFeatures()).isEmpty();
        var restored = analytics.filterDataByName("cohort.csv", null, Map.of("Age", "continuous"));
        assertThat(restored.getContinuousFeatures()).singleElement().satisfies(feature -> {
            var numeric = (org.taniwha.statistics.ContinuousFeatureStatistics) feature;
            assertThat(numeric.getCount()).isEqualTo(24);
            assertThat(numeric.getMean()).isEqualTo(2.5);
            assertThat(numeric.getSkewness()).isZero();
            assertThat(numeric.getModes()).containsExactly(1., 2., 3., 4.);
        });
    }

    @Test void entirelyMissingColumnsRemainExplicitlyUnavailableInsteadOfDisappearingFromMappings() throws Exception {
        initialise("Age,Value\n" + ",1\n".repeat(12));
        var response = analytics.filterDataByName("cohort.csv", null);
        assertThat(response.getDisclosurePolicyVersion()).isEqualTo(2);
        assertThat(response.getContinuousFeatures()).singleElement().satisfies(feature ->
                assertThat(feature.getFeatureName()).isEqualTo("Value"));
        assertThat(response.getOmittedFeatures()).singleElement().satisfies(feature -> {
            assertThat(feature.getFeatureName()).isEqualTo("Age");
            assertThat(feature.getCount()).isZero();
            assertThat(feature.getMissingValuesCount()).isZero();
            assertThat(feature.getReason()).startsWith("Suppressed for privacy:");
        });
    }
    @Test void unfilteredProfilingAndTypeRecalculationCannotResetHistory() throws Exception {
        initialise(csv()); analytics.filterDataByName("cohort.csv", less(32));
        analytics.processSingleFileOnDisk("cohort.csv");
        analytics.filterDataByName("cohort.csv", null, Map.of("Age", "continuous"));
        blocked(analytics.filterDataByName("cohort.csv", less(31)));
    }
    @Test void multiFileEndpointDoesNotReturnIntermediateProtectedSummaries() throws Exception {
        initialise(csv()); analytics.filterDataByName("cohort.csv", less(32));
        FileFilters request = new FileFilters(); request.setFileName("cohort.csv"); request.setFilters(less(31));
        blocked(analytics.filterMultipleFilesByName(List.of(request)).get(0));
    }
    @Test void asynchronousProfilingSharesHistoryAndCannotReleaseChangedData() throws Exception {
        initialise(csv());
        analytics.filterDataByName("cohort.csv", less(32));
        String jobId = jobs.createJob();
        analytics.startDiscoveryJob(jobId, List.of("cohort.csv"));
        jobs.getJob(jobId).getFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(jobs.getJob(jobId).getResults()).singleElement().satisfies(response ->
                assertThat(response.getDisclosurePolicyVersion()).isEqualTo(2));
        blocked(analytics.filterDataByName("cohort.csv", less(31)));

        Files.writeString(base.resolve("datasets/cohort.csv"), csv() + "99,C\n");
        String changedJob = jobs.createJob();
        analytics.startDiscoveryJob(changedJob, List.of("cohort.csv"));
        jobs.getJob(changedJob).getFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        // Per-file failures preserve the batch job contract, but never contain partial summaries.
        assertThat(jobs.getJob(changedJob).getState()).isEqualTo(ProcessingStatusDTO.State.DONE);
        assertThat(jobs.getJob(changedJob).getResults()).singleElement().satisfies(this::blocked);
    }
    @Test void absentAndSmallCohortsHaveTheSameWithheldPayload() throws Exception {
        initialise(csv());
        blocked(analytics.filterDataByName("cohort.csv", less(20)));
        blocked(analytics.filterDataByName("cohort.csv", less(21)));
        blocked(analytics.filterDataByName("cohort.csv", less(25)));
    }
    @Test void sparseHistogramTailsAreCombinedAndMissingnessDoesNotHideAnOtherwiseValidFeature() throws Exception {
        StringBuilder csv = new StringBuilder("Age,Score\n");
        for (int i = 0; i < 24; i++) csv.append(i == 23 ? 10000 : 20 + i).append(',').append(i == 0 ? "" : "42").append('\n');
        initialise(csv.toString());
        var response = analytics.processSingleFileOnDisk("cohort.csv");
        assertThat(response.getDisclosureMode()).isEqualTo("COHORT_AGGREGATES");
        assertThat(response.getContinuousFeatures()).extracting("featureName").containsExactlyInAnyOrder("Age", "Score");
        assertThat(response.getContinuousFeatures()).allSatisfy(feature -> {
            var numeric = (org.taniwha.statistics.ContinuousFeatureStatistics) feature;
            assertThat(numeric.getHistogram()).isNotEmpty().allMatch(n -> n == 0 || n >= 6);
            assertThat(numeric.getHistogram().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(numeric.getCount());
            assertThat(numeric.getOutliers()).isEmpty();
        });
    }

    @Test void categoricalOverrideCannotExposeOneRecordThroughCountsModesOrComplements() throws Exception {
        StringBuilder csv = new StringBuilder("Age,Flag\n");
        for (int i = 0; i < 24; i++) csv.append(20 + i).append(',').append(i == 0 ? "1" : "0").append('\n');
        initialise(csv.toString());
        var response = analytics.processSingleFileOnDisk("cohort.csv");
        assertThat(response.getContinuousFeatures()).extracting("featureName").containsExactly("Age");
        assertThat(response.getNumericPairSummaries()).noneMatch(pair -> pair.featureX().equals("Flag") || pair.featureY().equals("Flag"));
        response = analytics.filterDataByName("cohort.csv", null, Map.of("Flag", "categorical"));
        assertThat(response.getCategoricalFeatures()).isEmpty();
        assertThat(response.getOmittedFeatures()).singleElement()
                .extracting("featureName", "count", "missingValuesCount").containsExactly("Flag", 0L, 0L);
    }

    @Test void exactDateAndCategorySummariesWithholdSmallCellsAndMissingComplements() throws Exception {
        initialise("Age,Group,VisitDate\n" + "40,A,2020-01-01\n".repeat(6)
                + "40,B,2020-01-01\n".repeat(6) + "40,,2020-01-02\n");
        var response = analytics.processSingleFileOnDisk("cohort.csv");
        assertThat(response.getContinuousFeatures()).extracting("featureName").containsExactly("Age");
        assertThat(response.getCategoricalFeatures()).isEmpty();
        assertThat(response.getDateFeatures()).isEmpty();
        assertThat(response.getOmittedFeatures()).extracting("featureName").containsExactlyInAnyOrder("Group", "VisitDate");
    }

    @Test void aLaterFilterCannotSplitPreviouslyReleasedCategoryCountsIntoSmallCells() throws Exception {
        initialise("Age,Group\n" + "20,A\n".repeat(7) + "20,B\n".repeat(5)
                + "40,A\n".repeat(5) + "40,B\n".repeat(7));
        var whole = analytics.processSingleFileOnDisk("cohort.csv");
        assertThat(whole.getCategoricalFeatures()).singleElement()
                .hasFieldOrPropertyWithValue("categoryCounts", Map.of("A", 12, "B", 12));
        blocked(analytics.filterDataByName("cohort.csv", less(30)));
    }

    @Test void everyClinicalFeatureRetainsAHistogramDespiteUncommonOrdinalValues() throws Exception {
        StringBuilder data = new StringBuilder("Age,Dressing,Grooming,Mobility,Transfers,Stairs,Bathing,ToiletUse,Feeding\n");
        for (int row = 0; row < 96; row++) {
            data.append(30 + row % 55);
            for (int feature = 1; feature < 9; feature++) data.append(',').append(row == feature ? 0 : 5 * (row % 3 + 1));
            data.append('\n');
        }
        initialise(data.toString());
        var response = analytics.processSingleFileOnDisk("cohort.csv");
        assertThat(response.getContinuousFeatures()).hasSize(9).allSatisfy(feature -> {
            var numeric = (org.taniwha.statistics.ContinuousFeatureStatistics) feature;
            assertThat(numeric.getHistogram()).isNotEmpty().allMatch(count -> count == 0 || count >= 6);
            assertThat(numeric.getHistogram()).hasSameSizeAs(numeric.getBinRanges());
            assertThat(numeric.getHistogram().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(96);
            assertThat(numeric.getOutliers()).isEmpty();
        });
        assertThat(response.getOmittedFeatures()).isEmpty();
    }

    @Test void descriptiveMomentsDoNotHideVariablesButActualQueryDifferencesRemainProtected() throws Exception {
        StringBuilder csv = new StringBuilder("Age,Flag\n");
        for (int i = 0; i < 24; i++) {
            int flag = i < 6 || i == 12 ? 0 : 1;
            csv.append(20 + i).append(',').append(flag).append('\n');
        }
        initialise(csv.toString());
        var selected = analytics.filterDataByName("cohort.csv", less(32));
        assertThat(selected.getContinuousFeatures()).anyMatch(f -> f.getFeatureName().equals("Flag"));
        var whole = analytics.processSingleFileOnDisk("cohort.csv");
        assertThat(whole.getContinuousFeatures()).anyMatch(f -> f.getFeatureName().equals("Flag"));
        assertThat(whole.getNumericPairSummaries()).anyMatch(p -> p.featureX().equals("Flag") || p.featureY().equals("Flag"));
        blocked(analytics.filterDataByName("cohort.csv", less(31)));
    }

    @Test void pairedMomentsRemainAvailableWithoutReturningRareExactJointFrequencies() throws Exception {
        int[] counts = {1, 7, 8, 8};
        StringBuilder csv = new StringBuilder("X,Y\n");
        for (int x = 0; x < counts.length; x++) for (int repeat = 0; repeat < counts[x]; repeat++)
            csv.append(x).append(',').append(x * x).append('\n');
        initialise(csv.toString());
        var response = analytics.processSingleFileOnDisk("cohort.csv");
        assertThat(response.getContinuousFeatures()).hasSize(2);
        assertThat(response.getNumericPairSummaries()).anySatisfy(pair -> {
            assertThat(pair.featureX()).isEqualTo("X");
            assertThat(pair.featureY()).isEqualTo("Y");
            assertThat(pair.count()).isEqualTo(24);
            assertThat(pair.frequencies()).isEmpty();
        });
        assertThat(response.getCovariances().get("X")).containsKey("Y");
    }

    @Test void typedFilterRefreshKeepsAllOverridesAndRecomputesCompleteRelationships() throws Exception {
        StringBuilder csv = new StringBuilder("X,Y,Group1,Group2,VisitDate\n");
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int row = 0; row < 12; row++)
            csv.append(x).append(',').append(y).append(',').append(x == 0 ? 'A' : 'B')
                    .append(',').append(y == 0 ? 'U' : 'V').append(",2020-01-").append(x == 0 ? "01" : "05").append('\n');
        initialise(csv.toString());
        Map<String, String> types = Map.of("X", "categorical", "Y", "categorical");
        var response = analytics.filterDataByName("cohort.csv", null, types);
        assertThat(response.getDisclosurePolicyVersion()).isEqualTo(2);
        assertThat(response.getFeatureTypeSummaryVersion()).isEqualTo(1);
        assertThat(response.getFeatureTypes()).isEqualTo(types);
        assertThat(response.getCategoricalFeatures()).hasSize(4);
        assertThat(response.getContinuousFeatures()).isEmpty();
        assertThat(response.getDateFeatures()).hasSize(1);
        assertThat(response.getCovariances()).isEmpty();
        assertThat(response.getCategoricalPairSummaries()).hasSize(6);

        Map<String, Object> filters = Map.of("operator", "AND", "conditions", Map.of("X", Map.of(
                "conditions", List.of(Map.of("filterType", "categorical", "type", "equal", "value", "0")), "operators", List.of())));
        var filtered = analytics.filterDataByName("cohort.csv", filters, types);
        assertThat(filtered.getCategoricalFeatures()).hasSize(4).allSatisfy(feature -> assertThat(feature.getCount()).isEqualTo(24));
        assertThat(filtered.getFeatureTypes()).isEqualTo(types);
        var restored = analytics.filterDataByName("cohort.csv", null, Map.of("X", "continuous", "Y", "categorical"));
        assertThat(restored.getContinuousFeatures()).singleElement().satisfies(feature -> assertThat(feature.getFeatureName()).isEqualTo("X"));
        assertThat(restored.getCategoricalFeatures()).hasSize(3);
        assertThat(restored.getCovariances().get("X")).containsKey("VisitDate");
        var invalid = analytics.filterDataByName("cohort.csv", null, Map.of("Group1", "continuous"));
        assertThat(invalid.getMessage()).contains("explicit numerical mapping");
        assertThat(invalid.getDisclosurePolicyVersion()).isZero();
        assertThat(invalid.getContinuousFeatures()).isEmpty();
        assertThat(invalid.getCategoricalFeatures()).isEmpty();
    }

    @Test void typedRequestsCannotResetTheSharedReleaseHistory() throws Exception {
        initialise(csv());
        analytics.filterDataByName("cohort.csv", less(32), Map.of("Group", "categorical"));
        blocked(analytics.filterDataByName("cohort.csv", less(31), Map.of("Group", "categorical")));
        var invalid = analytics.filterDataByName("cohort.csv", null, Map.of("Age", "unexpected"));
        assertThat(invalid.getDisclosurePolicyVersion()).isZero();
    }

    @Test void safeCategoricalDateAndPairedSummariesStillPassTheSameGate() throws Exception {
        StringBuilder csv = new StringBuilder("X,Y,Group1,Group2,VisitDate\n");
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int row = 0; row < 12; row++) {
            csv.append(x).append(',').append(y).append(',').append(x == 0 ? 'A' : 'B')
                    .append(',').append(y == 0 ? 'U' : 'V').append(",2020-01-").append(x == 0 ? "01" : "05").append('\n');
        }
        initialise(csv.toString());
        var response = analytics.processSingleFileOnDisk("cohort.csv");
        assertThat(response.getDisclosurePolicyVersion()).isEqualTo(2);
        assertThat(response.getContinuousFeatures()).hasSize(2);
        assertThat(response.getCategoricalFeatures()).hasSize(2);
        assertThat(response.getDateFeatures()).hasSize(1);
        assertThat(response.getCategoricalPairSummaries()).singleElement().satisfies(pair -> {
            assertThat(pair.count()).isEqualTo(48);
            assertThat(pair.frequencies()).hasSize(4);
        });
        assertThat(response.getNumericPairSummaries()).hasSize(6);
        assertThat(response.getChiSquareTest()).hasSize(1);
        var recalculated = analytics.filterDataByName("cohort.csv", null, Map.of("X", "categorical"));
        assertThat(recalculated.getContinuousFeatures()).extracting("featureName").containsExactly("Y");
        assertThat(recalculated.getCategoricalFeatures()).extracting("featureName")
                .containsExactlyInAnyOrder("X", "Group1", "Group2");
        assertThat(recalculated.getDateFeatures()).extracting("featureName").containsExactly("VisitDate");
        assertThat(recalculated.getCovariances().get("Y")).containsKey("VisitDate");
        assertThat(recalculated.getCategoricalPairSummaries()).anyMatch(pair ->
                pair.featureX().equals("Group1") && pair.featureY().equals("Group2"));
    }
}
