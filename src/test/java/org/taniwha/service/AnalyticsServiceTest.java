package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.dto.FileFilters;
import org.taniwha.dto.ProcessingStatusDTO;
import org.taniwha.service.jobs.AnalyticsProcessingJobs;
import org.taniwha.privacy.DiscoveryDisclosureService;
import org.taniwha.privacy.DisclosureObservation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {

    @Mock
    private DataProcessingService dataProcessingService;

    @Mock
    private FileService fileService;

    @Mock
    private DiscoveryDisclosureService disclosure;

    @InjectMocks
    private AnalyticsService analyticsService;

    @BeforeEach
    void init() {
        MockitoAnnotations.openMocks(this);
        when(disclosure.begin(any())).thenAnswer(inv -> new DisclosureObservation(null));
        when(disclosure.release(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(dataProcessingService.matchesDiscoveryFilters(any(), any())).thenReturn(true);
    }

    @Test
    void processDatasetsOnDisk_emptyList_returnsEmpty() {
        var result = analyticsService.processDatasetsOnDisk(Collections.emptyList());
        assertThat(result).isEmpty();
    }

    @Test
    void processDatasetsOnDisk_singleFileNoRows_returnsNoDataMessage() throws IOException {
        String filename = "file1.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);
        doAnswer(inv -> null)
                .when(dataProcessingService).streamRows(eq(Paths.get("/tmp/" + filename)), any());
        var result = analyticsService.processDatasetsOnDisk(List.of(filename));
        assertThat(result).hasSize(1);
        AnalyticsResponseDTO dto = result.get(0);
        assertThat(dto.getFileName()).isEqualTo(filename);
        assertThat(dto.getMessage()).isEqualTo("No data found in file: " + filename);
    }

    @Test
    void filterMultipleFilesByName_nullFilters_usesSharedProfiler() {
        AnalyticsService spySvc = Mockito.spy(analyticsService);
        FileFilters ff = new FileFilters();
        ff.setFileName("f1");
        ff.setFilters(null);

        AnalyticsResponseDTO canned = new AnalyticsResponseDTO("OK1");
        doReturn(canned)
                .when(spySvc).filterDataByName("f1", null, null);

        var results = spySvc.filterMultipleFilesByName(List.of(ff));
        assertThat(results).extracting(AnalyticsResponseDTO::getMessage).containsExactly("OK1");
    }

    @Test
    void filterMultipleFilesByName_withFilters_usesFilterDataByName() {
        AnalyticsService spySvc = Mockito.spy(analyticsService);
        FileFilters ff = new FileFilters();
        ff.setFileName("f2");
        ff.setFilters(Map.of("col", 123));

        AnalyticsResponseDTO canned = new AnalyticsResponseDTO("OK2");
        doReturn(canned)
                .when(spySvc).filterDataByName(eq("f2"), anyMap(), isNull());

        var results = spySvc.filterMultipleFilesByName(List.of(ff));
        assertThat(results).extracting(AnalyticsResponseDTO::getMessage).containsExactly("OK2");
    }

    @Test
    void typedRefresh_emptyRecords_returnsNoData() throws Exception {
        String filename = "f1";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);
        stubStreamRows(filename, Collections.emptyList());

        var dto = analyticsService.filterDataByName(filename, null, Map.of("feat", "continuous"));
        assertThat(dto.getFileName()).isEqualTo(filename);
        assertThat(dto.getMessage()).isEqualTo("No data found in file: " + filename);
    }

    @Test
    void typedRefresh_oneRecord_suppressesFeature() throws Exception {
        String filename = "f2";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);
        stubStreamRows(filename, List.of(Map.of("col1", "42")));

        var dto = analyticsService.filterDataByName(filename, null, Map.of("col1", "continuous"));

        assertThat(dto.getMessage()).isEqualTo("Data processed successfully");
        assertThat(dto.getFileName()).isEqualTo(filename);

        assertThat(dto.getContinuousFeatures()).isEmpty();
        assertThat(dto.getOmittedFeatures())
                .singleElement()
                .extracting("featureName", "count", "reason")
                .containsExactly("col1", 0L, "Suppressed for privacy: 5 or fewer observations");
    }

    @Test
    void processSingleFileOnDisk_mixedColumns_fullPipeline() throws Exception {
        String filename = "mixed.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);

        Map<String, String> row = Map.of(
                "num", "3.14",
                "day", LocalDate.of(2020, 1, 2).toString(),
                "cat", "foo"
        );
        doAnswer(invocation -> {
            java.util.function.Consumer<Map<String, String>> consumer = invocation.getArgument(1);
            for (int i = 0; i < 6; i++) consumer.accept(row);
            return null;
        }).when(dataProcessingService).streamRows(eq(Paths.get("/tmp/" + filename)), any());

        AnalyticsResponseDTO dto = analyticsService.processSingleFileOnDisk(filename);

        assertThat(dto.getMessage())
                .isEqualTo("Data processed successfully");
        assertThat(dto.getContinuousFeatures())
                .anySatisfy(c -> assertThat(c.getFeatureName()).isEqualTo("num"));
        assertThat(dto.getDateFeatures())
                .anySatisfy(d -> assertThat(d.getFeatureName()).isEqualTo("day"));
        assertThat(dto.getCategoricalFeatures())
                .anySatisfy(c -> assertThat(c.getFeatureName()).isEqualTo("cat"));

        assertThat(dto.getOmittedFeatures()).isEmpty();
        assertThat(dto.getCovariances()).isNotNull();
        assertThat(dto.getPearsonCorrelations()).isNotNull();
        assertThat(dto.getSpearmanCorrelations()).isNotNull();
        assertThat(dto.getChiSquareTest()).isNotNull();
    }

    @Test
    void filterDataByName_emptyRecords_returnsNoMatchMessage() throws Exception {
        String fn = "f3";
        when(fileService.getDatasetFilePath(fn)).thenReturn("/tmp/" + fn);
        stubStreamRows(fn, Collections.emptyList());
        var dto = analyticsService.filterDataByName(fn, Map.of("a", 1));
        assertThat(dto.getMessage())
                .isEqualTo("No records match the provided filters for file: " + fn);
    }

    @Test
    void filterDataByName_exception_returnsErrorMessage() throws Exception {
        String fn = "f3";
        when(fileService.getDatasetFilePath(fn)).thenReturn("/tmp/" + fn);
        doThrow(new RuntimeException("uh oh")).when(dataProcessingService).streamRows(any(Path.class), any());

        var dto = analyticsService.filterDataByName(fn, Map.of("a", 1));
        assertThat(dto.getMessage())
                .isEqualTo("Error processing statistics.");
    }

    @Test
    @DisplayName("processSingleFileOnDisk: streamRows throws → error message")
    void processSingleFileOnDisk_streamRowsThrows_setsErrorMessage() throws Exception {
        String filename = "bad.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);
        doThrow(new IOException("ioerror"))
                .when(dataProcessingService).streamRows(eq(Paths.get("/tmp/" + filename)), any());

        AnalyticsResponseDTO dto = analyticsService.processSingleFileOnDisk(filename);
        assertThat(dto.getMessage())
                .isEqualTo("Error processing statistics.");
    }

    @Test
    @DisplayName("Typed refresh override=‘categorical’ sets only categoricalFeatures")
    void recalcFeature_overrideCategorical_setsCategoricalFeatures() throws Exception {
        String filename = "cf.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);
        stubStreamRows(filename, Collections.nCopies(6, Map.of("ccol", "foo")));

        AnalyticsResponseDTO dto =
                analyticsService.filterDataByName(filename, null, Map.of("ccol", "categorical"));

        assertThat(dto.getMessage()).isEqualTo("Data processed successfully");
        assertThat(dto.getCategoricalFeatures())
                .hasSize(1)
                .first().extracting("featureName").isEqualTo("ccol");
        assertThat(dto.getDateFeatures()).isNullOrEmpty();
        assertThat(dto.getContinuousFeatures()).isNullOrEmpty();
    }

    @Test
    @DisplayName("processSingleFileOnDisk omits identifier-like high-cardinality columns")
    void processSingleFileOnDisk_omitsHighCardinalityColumns() throws Exception {
        String filename = "highcard.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < 11; i++)
            rows.add(Map.of("u", "val" + i));

        doAnswer(inv -> {
            java.util.function.Consumer<Map<String, String>> consumer = inv.getArgument(1);
            rows.forEach(consumer);
            return null;
        }).when(dataProcessingService).streamRows(eq(Paths.get("/tmp/" + filename)), any());

        AnalyticsResponseDTO dto = analyticsService.processSingleFileOnDisk(filename);
        assertThat(dto.getCategoricalFeatures()).isEmpty();
        assertThat(dto.getOmittedFeatures())
                .singleElement()
                .extracting("featureName", "reason")
                .containsExactly("u", "Too many unique values (100.0%)");
    }

    @Test
    @DisplayName("Numerical recalculation rejects arbitrary encounter-order category encoding")
    void recalcFeature_overrideContinuous_requiresExplicitNumericMapping() throws Exception {
        String filename = "force.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);
        stubStreamRows(filename, Collections.nCopies(6, Map.of("fcol", "foo")));

        AnalyticsResponseDTO dto = analyticsService.filterDataByName(filename, null, Map.of("fcol", "continuous"))
                ;
        assertThat(dto.getMessage()).isEqualTo("This field cannot be converted to continuous without an explicit numerical mapping.");
        assertThat(dto.getContinuousFeatures()).isEmpty();
        assertThat(dto.getNumericPairSummaries()).isEmpty();
    }

    @Test
    @DisplayName("filterDataByName non-empty records returns success and statistics")
    void filterDataByName_success() throws Exception {
        String filename = "filter.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);

        List<Map<String, String>> recs = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            recs.add(Map.of("cat", "A", "num", Integer.toString(i)));
        }
        stubStreamRows(filename, recs);

        AnalyticsResponseDTO dto = analyticsService
                .filterDataByName(filename, Map.of("cat", "A"))
                ;

        assertThat(dto.getMessage()).isEqualTo("Data processed successfully");
        assertThat(dto.getCategoricalFeatures())
                .anySatisfy(fs -> assertThat(fs.getFeatureName()).isEqualTo("cat"));
        assertThat(dto.getContinuousFeatures())
                .anySatisfy(fs -> assertThat(fs.getFeatureName()).isEqualTo("num"));
    }

    @Test
    void processSingleFileOnDisk_fiveObservations_suppressesFeature() throws Exception {
        AnalyticsResponseDTO dto = processNumericRows("five.csv", 5);

        assertThat(dto.getContinuousFeatures()).isEmpty();
        assertThat(dto.getOmittedFeatures())
                .singleElement()
                .extracting("featureName", "count", "reason")
                .containsExactly("num", 0L, "Suppressed for privacy: 5 or fewer observations");
    }

    @Test
    void processSingleFileOnDisk_sixObservations_returnsFeature() throws Exception {
        AnalyticsResponseDTO dto = processNumericRows("six.csv", 6);

        assertThat(dto.getContinuousFeatures())
                .singleElement()
                .extracting("featureName", "count")
                .containsExactly("num", 6L);
        assertThat(dto.getOmittedFeatures()).isEmpty();
    }

    @Test
    void filterDataByName_fiveMatchingRecords_suppressesAllAnalytics() throws Exception {
        String filename = "filtered-five.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);
        stubStreamRows(filename, List.of(
                        Map.of("num", "1"), Map.of("num", "2"), Map.of("num", "3"),
                        Map.of("num", "4"), Map.of("num", "5")));

        AnalyticsResponseDTO dto = analyticsService
                .filterDataByName(filename, Map.of("group", "small"))
                ;

        assertThat(dto.getContinuousFeatures()).isEmpty();
        assertThat(dto.getCategoricalFeatures()).isEmpty();
        assertThat(dto.getDateFeatures()).isEmpty();
        assertThat(dto.getCovariances()).isEmpty();
        assertThat(dto.getPearsonCorrelations()).isEmpty();
        assertThat(dto.getSpearmanCorrelations()).isEmpty();
        assertThat(dto.getChiSquareTest()).isEmpty();
        assertThat(dto.getOmittedFeatures())
                .singleElement()
                .extracting("featureName", "count", "reason")
                .containsExactly("num", 0L, "Suppressed for privacy: 5 or fewer observations");
    }

    @Test
    void processSingleFileOnDisk_rareCategory_remainsAnAggregate() throws Exception {
        String filename = "rare-category.csv";
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) rows.add(Map.of("group", "common"));
        for (int i = 0; i < 5; i++) rows.add(Map.of("group", "rare"));
        stubStreamRows(filename, rows);
        var dto = analyticsService.processSingleFileOnDisk(filename);
        assertThat(dto.getCategoricalFeatures()).singleElement()
                .hasFieldOrPropertyWithValue("categoryCounts", Map.of("common", 6, "rare", 5));
        assertThat(dto.getOmittedFeatures()).isEmpty();
    }

    @Test
    void processSingleFileOnDisk_rareDate_remainsAnAggregate() throws Exception {
        String filename = "rare-date.csv";
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) rows.add(Map.of("visitDate", "2020-01-01"));
        for (int i = 0; i < 5; i++) rows.add(Map.of("visitDate", "2020-02-01"));
        stubStreamRows(filename, rows);
        var dto = analyticsService.processSingleFileOnDisk(filename);
        assertThat(dto.getDateFeatures()).singleElement()
                .hasFieldOrPropertyWithValue("dateHistogram", Map.of("2020-01-01", 6L, "2020-02-01", 5L));
        assertThat(dto.getOmittedFeatures()).isEmpty();
    }

    @Test
    void processSingleFileOnDisk_smallOutlierSet_doesNotReturnExactValues() throws Exception {
        String filename = "outliers.csv";
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) rows.add(Map.of("num", "0"));
        rows.add(Map.of("num", "100"));
        stubStreamRows(filename, rows);

        AnalyticsResponseDTO dto = analyticsService.processSingleFileOnDisk(filename);

        assertThat(dto.getContinuousFeatures())
                .singleElement()
                .extracting("outliers")
                .asList()
                .isEmpty();
    }

    private AnalyticsResponseDTO processNumericRows(String filename, int count) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) rows.add(Map.of("num", Integer.toString(i)));
        stubStreamRows(filename, rows);
        return analyticsService.processSingleFileOnDisk(filename);
    }

    @Test
    void streamedAndFilteredRelationshipsUseTheSamePatientPairs() throws Exception {
        var rows = rowsWithDifferentMissingPositions();
        stubStreamRows("pairs.csv", rows);
        when(dataProcessingService.matchesDiscoveryFilters(any(), any())).thenReturn(true);
        assertAlignedRelationships(analyticsService.processSingleFileOnDisk("pairs.csv"));
        assertAlignedRelationships(analyticsService.filterDataByName("pairs.csv", Map.of()));
    }

    @Test
    void typeRecalculationReusesAlignedRelationships() throws Exception {
        stubStreamRows("pairs.csv", rowsWithDifferentMissingPositions());
        var result = analyticsService.filterDataByName("pairs.csv", null, Map.of("x", "continuous"));
        assertAlignedRelationships(result);
        assertThat(result.getFeatureTypeSummaryVersion()).isEqualTo(1);
        assertThat(result.getFeatureTypes()).containsEntry("x", "continuous");
        assertThat(result.getContinuousFeatures()).extracting("featureName").containsExactlyInAnyOrder("x", "y");
        verify(dataProcessingService, never()).extractDataFromPath(any());
    }

    @Test
    void asyncDiscoveryReusesAlignedRelationships() throws Exception {
        Path csv = tempDir.resolve("pairs.csv");
        Files.writeString(csv, "x,y\n1,2\n");
        stubStreamRows("pairs.csv", rowsWithDifferentMissingPositions());
        when(fileService.getDatasetFilePath("pairs.csv")).thenReturn(csv.toString());
        doAnswer(invocation -> {
            java.util.function.Consumer<Map<String, String>> consumer = invocation.getArgument(1);
            rowsWithDifferentMissingPositions().forEach(consumer);
            return null;
        }).when(dataProcessingService).streamRows(eq(csv), any());
        AnalyticsProcessingJobs realJobs = new AnalyticsProcessingJobs();
        org.springframework.test.util.ReflectionTestUtils.setField(analyticsService, "jobs", realJobs);
        String jobId = realJobs.createJob();
        analyticsService.startDiscoveryJob(jobId, List.of("pairs.csv"));
        long deadline = System.currentTimeMillis() + 5000;
        while (realJobs.getJob(jobId).getState() == ProcessingStatusDTO.State.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(realJobs.getJob(jobId).getState()).isEqualTo(ProcessingStatusDTO.State.DONE);
        var asyncResult = realJobs.getJob(jobId).getResults().get(0);
        assertAlignedRelationships(asyncResult);
        assertThat(asyncResult).usingRecursiveComparison()
                .isEqualTo(analyticsService.processSingleFileOnDisk("pairs.csv"));
        assertThat(asyncResult).usingRecursiveComparison()
                .isEqualTo(analyticsService.filterDataByName("pairs.csv", Map.of()));
        assertThat(asyncResult.getOmittedFeatures()).extracting("featureName").contains("Unavailable");
    }

    @Test
    void suppressesSmallPairwiseCohortsEvenWhenBothFeaturesAreReleased() throws Exception {
        var rows = new ArrayList<Map<String, String>>();
        for (int i = 0; i < 10; i++) rows.add(Map.of("x", i < 6 ? String.valueOf(i) : "", "y", i >= 4 ? String.valueOf(i * 2) : ""));
        stubStreamRows("few-pairs.csv", rows);
        var result = analyticsService.processSingleFileOnDisk("few-pairs.csv");
        assertThat(result.getContinuousFeatures()).hasSize(2);
        assertThat(result.getCovariances()).isEmpty();
        assertThat(result.getPearsonCorrelations()).isEmpty();
        assertThat(result.getSpearmanCorrelations()).isEmpty();
    }

    private List<Map<String, String>> rowsWithDifferentMissingPositions() {
        var rows = new ArrayList<Map<String, String>>();
        for (int i = 1; i <= 10; i++) rows.add(Map.of("x", i == 1 ? "" : String.valueOf(i), "y", i == 2 ? "" : String.valueOf(i * 2), "Unavailable", ""));
        return rows;
    }

    @Test
    void discoveryReleasesOnlyAggregatesIncludingForOutliersAndDates() throws Exception {
        var rows = new ArrayList<Map<String, String>>();
        for (int i = 0; i < 100; i++) rows.add(Map.of("x", "0", "date", "2020-01-01"));
        for (int i = 0; i < 6; i++) rows.add(Map.of("x", "10000", "date", "2030-01-01"));
        stubStreamRows("aggregates.csv", rows);
        var result = analyticsService.processSingleFileOnDisk("aggregates.csv");
        var numeric = (org.taniwha.statistics.ContinuousFeatureStatistics) result.getContinuousFeatures().get(0);
        assertThat(numeric.getOutliers()).isEmpty();
        assertThat(numeric.getValueCounts()).hasSize(2).allSatisfy(cell -> assertThat(cell.count()).isGreaterThanOrEqualTo(6));
        assertThat(result.getDateFeatures().get(0).getOutliers()).isEmpty();
        assertThat(result.getNumericPairSummaries()).anySatisfy(pair -> {
            assertThat(pair.featureX()).isEqualTo("date"); assertThat(pair.featureY()).isEqualTo("x");
            assertThat(pair.count()).isEqualTo(106);
            assertThat(pair.frequencies()).hasSize(2).allSatisfy(cell -> assertThat(cell.count()).isGreaterThanOrEqualTo(6));
        });
    }

    private void assertAlignedRelationships(AnalyticsResponseDTO result) {
        assertThat(result.getRelationshipSummaryVersion()).isEqualTo(1);
        assertThat(result.getNumericPairSummaries()).anySatisfy(pair -> {
            assertThat(pair.featureX()).isEqualTo("x"); assertThat(pair.featureY()).isEqualTo("y");
            assertThat(pair.count()).isEqualTo(8); assertThat(pair.frequencies()).isEmpty();
        });
        assertThat(result.getCovariances().get("x").get("y")).isCloseTo(12.0, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.getPearsonCorrelations().get("x").get("y")).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.getSpearmanCorrelations().get("x").get("y")).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-12));
    }

    private void stubStreamRows(String filename, List<Map<String, String>> rows) throws Exception {
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);
        doAnswer(invocation -> {
            java.util.function.Consumer<Map<String, String>> consumer = invocation.getArgument(1);
            rows.forEach(consumer);
            return null;
        }).when(dataProcessingService).streamRows(eq(Paths.get("/tmp/" + filename)), any());
    }

    // isAnyHugeForDiscovery – covers isHugeFile, estimateCsvRows

    @TempDir
    Path tempDir;

    @Test
    void isAnyHugeForDiscovery_smallCsvFile_returnsFalse() throws Exception {
        Path csv = tempDir.resolve("small.csv");
        // Write a small CSV (well below HUGE_BYTES_THRESHOLD=1MB and HUGE_ROWS_THRESHOLD=5000)
        StringBuilder sb = new StringBuilder("col1,col2\n");
        for (int i = 0; i < 10; i++) sb.append(i).append(",val").append(i).append("\n");
        Files.writeString(csv, sb.toString());

        when(fileService.getDatasetFilePath("small.csv")).thenReturn(csv.toString());

        boolean result = analyticsService.isAnyHugeForDiscovery(List.of("small.csv"));

        assertThat(result).isFalse();
    }

    @Test
    void isAnyHugeForDiscovery_emptyList_returnsFalse() {
        boolean result = analyticsService.isAnyHugeForDiscovery(List.of());
        assertThat(result).isFalse();
    }

    @Test
    void isAnyHugeForDiscovery_largeCsv_returnsTrue() throws Exception {
        Path csv = tempDir.resolve("large.csv");
        // Write a CSV with > 5000 rows to trigger HUGE_ROWS_THRESHOLD
        StringBuilder sb = new StringBuilder("col1,col2\n");
        for (int i = 0; i < 6000; i++) sb.append(i).append(",value").append(i).append("\n");
        Files.writeString(csv, sb.toString());

        when(fileService.getDatasetFilePath("large.csv")).thenReturn(csv.toString());

        boolean result = analyticsService.isAnyHugeForDiscovery(List.of("large.csv"));

        assertThat(result).isTrue();
    }

    @Test
    void isAnyHugeForDiscovery_exceptionFromFileService_returnsFalse() {
        when(fileService.getDatasetFilePath("err.csv"))
                .thenThrow(new RuntimeException("IO error"));

        boolean result = analyticsService.isAnyHugeForDiscovery(List.of("err.csv"));

        assertThat(result).isFalse();
    }

    @Test
    void isAnyHugeForDiscovery_nonExistentFile_returnsFalse() {
        when(fileService.getDatasetFilePath("ghost.csv"))
                .thenReturn("/nonexistent/path/ghost.csv");

        boolean result = analyticsService.isAnyHugeForDiscovery(List.of("ghost.csv"));

        assertThat(result).isFalse();
    }

    // startDiscoveryJob – covers async processing path

    @Test
    void startDiscoveryJob_singleSmallFile_completesJob() throws Exception {
        Path csv = tempDir.resolve("data.csv");
        StringBuilder sb = new StringBuilder("col1,col2\n");
        for (int i = 0; i < 5; i++) sb.append(i).append(",val").append(i).append("\n");
        Files.writeString(csv, sb.toString());

        when(fileService.getDatasetFilePath("data.csv")).thenReturn(csv.toString());

        // Use real AnalyticsProcessingJobs
        AnalyticsProcessingJobs realJobs = new AnalyticsProcessingJobs();
        // Inject real jobs via reflection
        org.springframework.test.util.ReflectionTestUtils.setField(analyticsService, "jobs", realJobs);

        // DataProcessingService needs real CSV reading – stub streamRows to provide data
        doAnswer(inv -> {
            java.util.function.Consumer<Map<String, String>> consumer = inv.getArgument(1);
            consumer.accept(Map.of("col1", "0", "col2", "val0"));
            consumer.accept(Map.of("col1", "1", "col2", "val1"));
            return null;
        }).when(dataProcessingService).streamRows(any(Path.class), any());

        String jobId = realJobs.createJob();
        analyticsService.startDiscoveryJob(jobId, List.of("data.csv"));

        long deadline = System.currentTimeMillis() + 5_000;
        while (realJobs.getJob(jobId).getState() == ProcessingStatusDTO.State.RUNNING
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertThat(realJobs.getJob(jobId).getState()).isIn(
                ProcessingStatusDTO.State.DONE,
                ProcessingStatusDTO.State.ERROR);
    }
}
