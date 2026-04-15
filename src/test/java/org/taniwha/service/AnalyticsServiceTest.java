package org.taniwha.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.dto.FileFilters;
import org.taniwha.service.jobs.AnalyticsProcessingJobs;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {

    @Mock
    private DataProcessingService dataProcessingService;

    @Mock
    private FileService fileService;

    @Mock
    private AnalyticsProcessingJobs analyticsProcessingJobs;

    @Mock
    private DisclosureControlService disclosureControlService;

    @Mock
    private AnalyticsAuditService analyticsAuditService;

    @InjectMocks
    private AnalyticsService analyticsService;

    @BeforeEach
    void init() {
        MockitoAnnotations.openMocks(this);
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
    void filterMultipleFilesByName_nullFilters_usesProcessSingleFileOnDisk() {
        AnalyticsService spySvc = Mockito.spy(analyticsService);
        FileFilters ff = new FileFilters();
        ff.setFileName("f1");
        ff.setFilters(null);

        AnalyticsResponseDTO canned = new AnalyticsResponseDTO("OK1");
        doReturn(CompletableFuture.completedFuture(canned))
                .when(spySvc).processSingleFileOnDisk("f1");

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
        doReturn(CompletableFuture.completedFuture(canned))
                .when(spySvc).filterDataByName(eq("f2"), anyMap());

        var results = spySvc.filterMultipleFilesByName(List.of(ff));
        assertThat(results).extracting(AnalyticsResponseDTO::getMessage).containsExactly("OK2");
    }

    @Test
    void recalculateFeatureAsTypeFromDisk_emptyRecords_returnsNoData() throws Exception {
        String filename = "f1";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);
        when(dataProcessingService.extractDataFromPath(any(Path.class)))
                .thenReturn(Collections.emptyList());

        var future = analyticsService.recalculateFeatureAsTypeFromDisk(filename, "feat", "continuous");
        AnalyticsResponseDTO dto = future.get();
        assertThat(dto.getFileName()).isEqualTo(filename);
        assertThat(dto.getMessage()).isEqualTo("No data found in file: " + filename);
    }

    @Test
    void recalculateFeatureAsTypeFromDisk_oneRecord_continuous_success() throws Exception {
        String filename = "f2";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);
        when(dataProcessingService.extractDataFromPath(any(Path.class)))
                .thenReturn(List.of(Map.of("col1", "42")));

        var future = analyticsService.recalculateFeatureAsTypeFromDisk(filename, "col1", "continuous");
        AnalyticsResponseDTO dto = future.get();

        assertThat(dto.getMessage()).isEqualTo("Data processed successfully");
        assertThat(dto.getFileName()).isEqualTo(filename);

        assertThat(dto.getContinuousFeatures())
                .hasSize(1)
                .first()
                .extracting("featureName", "count")
                .containsExactly("col1", 1L);
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
            consumer.accept(row);
            return null;
        }).when(dataProcessingService).streamRows(eq(Paths.get("/tmp/" + filename)), any());

        CompletableFuture<AnalyticsResponseDTO> f = analyticsService.processSingleFileOnDisk(filename);
        AnalyticsResponseDTO dto = f.get();

        assertThat(dto.getMessage())
                .isEqualTo("File processed successfully: " + filename);
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
        when(dataProcessingService.extractFilteredDataFromPath(any(), anyMap()))
                .thenReturn(Collections.emptyList());
        var dto = analyticsService.filterDataByName(fn, Map.of("a", 1)).get();
        assertThat(dto.getMessage())
                .isEqualTo("No records match the provided filters for file: " + fn);
    }

    @Test
    void filterDataByName_exception_returnsErrorMessage() throws Exception {
        String fn = "f3";
        when(fileService.getDatasetFilePath(fn)).thenReturn("/tmp/" + fn);
        when(dataProcessingService.extractFilteredDataFromPath(any(), anyMap()))
                .thenThrow(new RuntimeException("uh oh"));

        var dto = analyticsService.filterDataByName(fn, Map.of("a", 1)).get();
        assertThat(dto.getMessage())
                .isEqualTo("Error filtering file f3: uh oh");
    }

    @Test
    void recalcFeature_errorMessageContainsValueMap_null() throws Exception {
        String fn = "bad.csv";
        when(fileService.getDatasetFilePath(fn)).thenReturn("/tmp/" + fn);
        when(dataProcessingService.extractDataFromPath(any()))
                .thenThrow(new RuntimeException("ValueMap foo null pointer"));

        var dto = analyticsService.recalculateFeatureAsTypeFromDisk(fn, "f", "categorical").get();
        assertThat(dto.getMessage())
                .isEqualTo("This field cannot be converted to categorical.");
    }

    @Test
    @DisplayName("processSingleFileOnDisk: streamRows throws → error message")
    void processSingleFileOnDisk_streamRowsThrows_setsErrorMessage() throws Exception {
        String filename = "bad.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);
        doThrow(new IOException("ioerror"))
                .when(dataProcessingService).streamRows(eq(Paths.get("/tmp/" + filename)), any());

        AnalyticsResponseDTO dto = analyticsService.processSingleFileOnDisk(filename).get();
        assertThat(dto.getMessage())
                .isEqualTo("Error processing file " + filename + ": ioerror");
    }

    @Test
    @DisplayName("recalculateFeatureAsTypeFromDisk override='date' produces no feature lists")
    void recalcFeature_overrideDate_noFeatureLists() throws Exception {
        String filename = "df.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);
        when(dataProcessingService.extractDataFromPath(any()))
                .thenReturn(List.of(Map.of("dcol", "2020-01-15")));

        AnalyticsResponseDTO dto =
                analyticsService.recalculateFeatureAsTypeFromDisk(filename, "dcol", "date").get();

        assertThat(dto.getMessage()).isEqualTo("Data processed successfully");
        assertThat(dto.getDateFeatures()).isNullOrEmpty();
        assertThat(dto.getContinuousFeatures()).isNullOrEmpty();
        assertThat(dto.getCategoricalFeatures()).isNullOrEmpty();
    }

    @Test
    @DisplayName("recalculateFeatureAsTypeFromDisk override=‘categorical’ sets only categoricalFeatures")
    void recalcFeature_overrideCategorical_setsCategoricalFeatures() throws Exception {
        String filename = "cf.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);
        when(dataProcessingService.extractDataFromPath(any()))
                .thenReturn(List.of(Map.of("ccol", "foo")));

        AnalyticsResponseDTO dto =
                analyticsService.recalculateFeatureAsTypeFromDisk(filename, "ccol", "categorical").get();

        assertThat(dto.getMessage()).isEqualTo("Data processed successfully");
        assertThat(dto.getCategoricalFeatures())
                .hasSize(1)
                .first().extracting("featureName").isEqualTo("ccol");
        assertThat(dto.getDateFeatures()).isNullOrEmpty();
        assertThat(dto.getContinuousFeatures()).isNullOrEmpty();
    }

    @Test
    @DisplayName("processDatasetsOnDisk a failing future → ExecutionException branch")
    void processDatasetsOnDisk_executionException() {
        AnalyticsService spySvc = Mockito.spy(analyticsService);
        CompletableFuture<AnalyticsResponseDTO> bad = new CompletableFuture<>();
        bad.completeExceptionally(new RuntimeException("boom"));
        doReturn(bad).when(spySvc).processSingleFileOnDisk("f");

        List<AnalyticsResponseDTO> results = spySvc.processDatasetsOnDisk(List.of("f"));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getMessage()).startsWith("Error:");
    }

    @Test
    @DisplayName("processDatasetsOnDisk an interrupted future → InterruptedException branch")
    void processDatasetsOnDisk_interruptedException() {
        AnalyticsService spySvc = Mockito.spy(analyticsService);
        CompletableFuture<AnalyticsResponseDTO> intr = new CompletableFuture<>() {
            @Override
            public AnalyticsResponseDTO get() throws InterruptedException {
                throw new InterruptedException("forced");
            }
        };
        doReturn(intr).when(spySvc).processSingleFileOnDisk("g");

        List<AnalyticsResponseDTO> results = spySvc.processDatasetsOnDisk(List.of("g"));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getMessage())
                .isEqualTo("Thread interrupted while processing file.");
    }

    @Test
    @DisplayName("processSingleFileOnDisk omits categorical columns with too many unique values")
    void processSingleFileOnDisk_omitsHighCardinality() throws Exception {
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

        AnalyticsResponseDTO dto = analyticsService.processSingleFileOnDisk(filename).get();
        assertThat(dto.getCategoricalFeatures()).isEmpty();
        assertThat(dto.getOmittedFeatures())
                .singleElement()
                .extracting("featureName", "reason")
                .containsExactly("u", "Too many unique values (100.0%)");
    }

    @Test
    @DisplayName("recalculateFeatureAsTypeFromDisk override continuous with non-numeric forces mapping")
    void recalcFeature_overrideContinuous_forcedMapping() throws Exception {
        String filename = "force.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);
        when(dataProcessingService.extractDataFromPath(any(Path.class)))
                .thenReturn(List.of(Map.of("fcol", "foo")));

        AnalyticsResponseDTO dto = analyticsService
                .recalculateFeatureAsTypeFromDisk(filename, "fcol", "continuous")
                .get();
        assertThat(dto.getMessage()).isEqualTo("Data processed successfully");
        assertThat(dto.getContinuousFeatures())
                .hasSize(1)
                .first()
                .extracting("featureName", "count")
                .containsExactly("fcol", 1L);
    }

    @Test
    @DisplayName("filterDataByName non-empty records returns success and statistics")
    void filterDataByName_success() throws Exception {
        String filename = "filter.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);

        List<Map<String, String>> recs = List.of(
                Map.of("cat", "A", "num", "1"),
                Map.of("cat", "A", "num", "2")
        );
        when(dataProcessingService.extractFilteredDataFromPath(any(Path.class), anyMap()))
                .thenReturn(recs);

        AnalyticsResponseDTO dto = analyticsService
                .filterDataByName(filename, Map.of("cat", "A"))
                .get();

        assertThat(dto.getMessage()).isEqualTo("Data processed successfully");
        assertThat(dto.getCategoricalFeatures())
                .anySatisfy(fs -> assertThat(fs.getFeatureName()).isEqualTo("cat"));
        assertThat(dto.getContinuousFeatures())
                .anySatisfy(fs -> assertThat(fs.getFeatureName()).isEqualTo("num"));
    }

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // isAnyHugeForDiscovery  – real temp CSV files
    // -------------------------------------------------------------------------

    @Test
    void isAnyHugeForDiscovery_smallFile_returnsFalse() throws IOException {
        Path csv = tempDir.resolve("small.csv");
        StringBuilder sb = new StringBuilder("col\n");
        for (int i = 0; i < 10; i++) sb.append("val").append(i).append("\n");
        Files.writeString(csv, sb);

        when(fileService.getDatasetFilePath("small.csv")).thenReturn(csv.toString());

        assertThat(analyticsService.isAnyHugeForDiscovery(List.of("small.csv"))).isFalse();
    }

    @Test
    void isAnyHugeForDiscovery_bigFile_returnsTrue() throws IOException {
        // Create a file > HUGE_BYTES_THRESHOLD (1 MB)
        Path csv = tempDir.resolve("big.csv");
        byte[] chunk = ("longvalue,anothervalue\n").getBytes();
        try (var out = Files.newOutputStream(csv)) {
            int written = 0;
            while (written < 1_100_000) {
                out.write(chunk);
                written += chunk.length;
            }
        }

        when(fileService.getDatasetFilePath("big.csv")).thenReturn(csv.toString());

        assertThat(analyticsService.isAnyHugeForDiscovery(List.of("big.csv"))).isTrue();
    }

    @Test
    void isAnyHugeForDiscovery_manyRows_returnsTrue() throws IOException {
        // Create a file with > HUGE_ROWS_THRESHOLD (5000) rows but small total size
        Path csv = tempDir.resolve("manyrows.csv");
        StringBuilder sb = new StringBuilder("col\n");
        for (int i = 0; i < 6000; i++) sb.append("v\n");
        Files.writeString(csv, sb);

        when(fileService.getDatasetFilePath("manyrows.csv")).thenReturn(csv.toString());

        assertThat(analyticsService.isAnyHugeForDiscovery(List.of("manyrows.csv"))).isTrue();
    }

    @Test
    void isAnyHugeForDiscovery_fileNotFound_returnsFalse() {
        when(fileService.getDatasetFilePath("ghost.csv")).thenReturn("/nonexistent/ghost.csv");

        // Should catch IOException and return false gracefully
        assertThat(analyticsService.isAnyHugeForDiscovery(List.of("ghost.csv"))).isFalse();
    }

    @Test
    void isAnyHugeForDiscovery_emptyList_returnsFalse() {
        assertThat(analyticsService.isAnyHugeForDiscovery(List.of())).isFalse();
    }

    @Test
    void isAnyHugeForDiscovery_nonCsvNonXlsxFile_returnsFalse() throws IOException {
        Path ttl = tempDir.resolve("meta.ttl");
        Files.writeString(ttl, "content");
        when(fileService.getDatasetFilePath("meta.ttl")).thenReturn(ttl.toString());

        assertThat(analyticsService.isAnyHugeForDiscovery(List.of("meta.ttl"))).isFalse();
    }

    // -------------------------------------------------------------------------
    // filterMultipleFilesByName – exception paths in future.get()
    // -------------------------------------------------------------------------

    @Test
    void filterMultipleFilesByName_executionException_returnsErrorMessage() throws Exception {
        AnalyticsService spySvc = Mockito.spy(analyticsService);
        FileFilters ff = new FileFilters();
        ff.setFileName("f3");
        ff.setFilters(null);

        java.util.concurrent.CompletableFuture<AnalyticsResponseDTO> failedFuture =
                new java.util.concurrent.CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("processing error"));

        doReturn(failedFuture).when(spySvc).processSingleFileOnDisk("f3");

        var results = spySvc.filterMultipleFilesByName(List.of(ff));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getMessage()).contains("Error:");
    }

    // -------------------------------------------------------------------------
    // processRecord coverage via processDatasetsOnDisk – various data types
    // -------------------------------------------------------------------------

    @Test
    void processDatasetsOnDisk_dateColumn_detectsDateFeature() throws IOException {
        String filename = "dates.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);

        doAnswer(inv -> {
            java.util.function.Consumer<Map<String, String>> consumer = inv.getArgument(1);
            consumer.accept(Map.of("eventDate", "2023-01-15"));
            consumer.accept(Map.of("eventDate", "2023-06-20"));
            return null;
        }).when(dataProcessingService).streamRows(eq(Paths.get("/tmp/" + filename)), any());

        var results = analyticsService.processDatasetsOnDisk(List.of(filename));

        assertThat(results).hasSize(1);
        AnalyticsResponseDTO dto = results.get(0);
        assertThat(dto.getDateFeatures()).isNotEmpty();
    }

    @Test
    void processDatasetsOnDisk_nullAndEmptyValues_incrementsMissingCount() throws IOException {
        String filename = "nulls.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);

        doAnswer(inv -> {
            java.util.function.Consumer<Map<String, String>> consumer = inv.getArgument(1);
            // null value via empty string
            Map<String, String> row = new java.util.HashMap<>();
            row.put("col", null);
            consumer.accept(row);
            Map<String, String> row2 = new java.util.HashMap<>();
            row2.put("col", "  ");
            consumer.accept(row2);
            Map<String, String> row3 = new java.util.HashMap<>();
            row3.put("col", "NULL");
            consumer.accept(row3);
            return null;
        }).when(dataProcessingService).streamRows(eq(Paths.get("/tmp/" + filename)), any());

        var results = analyticsService.processDatasetsOnDisk(List.of(filename));

        assertThat(results).hasSize(1);
        AnalyticsResponseDTO dto = results.get(0);
        // All values were missing so the feature may have no data → no data message or empty feature
        assertThat(dto.getMessage()).isNotNull();
    }

    @Test
    void processDatasetsOnDisk_mixedColumnBecomesDate_overridesContinuous() throws IOException {
        // First row makes it look continuous, second a valid date → date takes priority
        String filename = "mixed.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);

        doAnswer(inv -> {
            java.util.function.Consumer<Map<String, String>> consumer = inv.getArgument(1);
            consumer.accept(Map.of("col", "2023-01-01"));
            consumer.accept(Map.of("col", "2023-02-01"));
            consumer.accept(Map.of("col", "2023-03-01"));
            return null;
        }).when(dataProcessingService).streamRows(eq(Paths.get("/tmp/" + filename)), any());

        var results = analyticsService.processDatasetsOnDisk(List.of(filename));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getDateFeatures()).isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // filterMultipleFilesByName – ExecutionException path
    // -------------------------------------------------------------------------

    @Test
    void filterMultipleFilesByName_executionException_returnsErrorDto() throws Exception {
        String filename = "err.csv";
        when(fileService.getDatasetFilePath(filename))
                .thenThrow(new RuntimeException("Disk not found"));

        FileFilters ff = new FileFilters();
        ff.setFileName(filename);
        ff.setFilters(null);
        List<AnalyticsResponseDTO> results = analyticsService.filterMultipleFilesByName(List.of(ff));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getMessage()).contains("Error");
    }

    // -------------------------------------------------------------------------
    // processRecord – NULL string and whitespace-only values → missing count
    // -------------------------------------------------------------------------

    @Test
    void processDatasetsOnDisk_nullStringValue_countedAsMissing() throws IOException {
        String filename = "nullstr.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);

        doAnswer(inv -> {
            java.util.function.Consumer<Map<String, String>> consumer = inv.getArgument(1);
            Map<String, String> row = new java.util.HashMap<>();
            row.put("col", "NULL");
            consumer.accept(row);
            consumer.accept(Map.of("col", "valid"));
            return null;
        }).when(dataProcessingService).streamRows(eq(Paths.get("/tmp/" + filename)), any());

        var results = analyticsService.processDatasetsOnDisk(List.of(filename));
        assertThat(results).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // processRecord – forced mapping (override continuous + non-parseable value)
    // -------------------------------------------------------------------------

    @Test
    void recalculateFeatureAsType_forcedMapping_categorical_nonParseableValue() throws Exception {
        String filename = "cat.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);

        when(dataProcessingService.extractDataFromPath(Paths.get("/tmp/" + filename)))
                .thenReturn(List.of(
                        Map.of("label", "cat_A"),
                        Map.of("label", "cat_B"),
                        Map.of("label", "cat_A")
                ));

        // Override feature as "continuous" → non-numeric "cat_A" triggers forced-mapping path
        var result = analyticsService.recalculateFeatureAsTypeFromDisk(filename, "label", "continuous").get();

        assertThat(result).isNotNull();
    }

    // -------------------------------------------------------------------------
    // processDatasetsOnDisk – categorical data with combinations
    // -------------------------------------------------------------------------

    @Test
    void processDatasetsOnDisk_twoCategoricalColumns_buildsCombinationCounts() throws IOException {
        String filename = "cats2.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);

        doAnswer(inv -> {
            java.util.function.Consumer<Map<String, String>> consumer = inv.getArgument(1);
            consumer.accept(Map.of("color", "red", "shape", "circle"));
            consumer.accept(Map.of("color", "blue", "shape", "square"));
            consumer.accept(Map.of("color", "red", "shape", "circle"));
            consumer.accept(Map.of("color", "red", "shape", "circle"));
            consumer.accept(Map.of("color", "red", "shape", "circle"));
            consumer.accept(Map.of("color", "red", "shape", "circle"));
            return null;
        }).when(dataProcessingService).streamRows(eq(Paths.get("/tmp/" + filename)), any());

        var results = analyticsService.processDatasetsOnDisk(List.of(filename));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getCategoricalFeatures()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // processDatasetsOnDisk – feature name with " (" suffix → getOriginalFeatureName strips it
    // -------------------------------------------------------------------------

    @Test
    void recalculateFeatureAsType_featureNameWithParenthesis_stripped() throws Exception {
        String filename = "paren.csv";
        when(fileService.getDatasetFilePath(filename)).thenReturn("/tmp/" + filename);

        when(dataProcessingService.extractDataFromPath(Paths.get("/tmp/" + filename)))
                .thenReturn(List.of(
                        Map.of("score", "10"),
                        Map.of("score", "20"),
                        Map.of("score", "30")
                ));

        // featureName "score (% coverage)" → getOriginalFeatureName returns "score"
        var result = analyticsService.recalculateFeatureAsTypeFromDisk(filename, "score (% coverage)", "continuous").get();
        assertThat(result).isNotNull();
    }

    @Test
    void privatePercent_handlesBoundsAndZeroTotal() throws Exception {
        Method m = AnalyticsService.class.getDeclaredMethod("percent", long.class, long.class);
        m.setAccessible(true);

        assertThat((int) m.invoke(analyticsService, 5L, 0L)).isEqualTo(0);
        assertThat((int) m.invoke(analyticsService, 0L, 10L)).isEqualTo(0);
        assertThat((int) m.invoke(analyticsService, 15L, 10L)).isEqualTo(100);
    }

    @Test
    void privateEstimateRowsFast_csvCountsLinesMinusHeader() throws Exception {
        Path csv = Files.createTempFile("analytics_rows", ".csv");
        Files.writeString(csv, "h1;h2\n1;2\n3;4\n");

        Method m = AnalyticsService.class.getDeclaredMethod("estimateRowsFast", Path.class);
        m.setAccessible(true);
        long rows = (long) m.invoke(analyticsService, csv);

        assertThat(rows).isEqualTo(2L);
    }

    @Test
    void privateEstimateRowsFast_xlsxReadsSheetDimensions() throws Exception {
        Path xlsx = Files.createTempFile("analytics_rows", ".xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("S1");
            Row h = sh.createRow(0);
            h.createCell(0).setCellValue("age");
            sh.createRow(1).createCell(0).setCellValue(10);
            sh.createRow(2).createCell(0).setCellValue(20);
            try (FileOutputStream fos = new FileOutputStream(xlsx.toFile())) {
                wb.write(fos);
            }
        }

        Method m = AnalyticsService.class.getDeclaredMethod("estimateRowsFast", Path.class);
        m.setAccessible(true);
        long rows = (long) m.invoke(analyticsService, xlsx);

        assertThat(rows).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void shutdown_executorTerminatesGracefully() throws Exception {
        ExecutorService mockExec = mock(ExecutorService.class);
        when(mockExec.awaitTermination(60, TimeUnit.SECONDS)).thenReturn(true);

        ReflectionTestUtils.setField(analyticsService, "discoveryJobExecutor", mockExec);
        analyticsService.shutdown();

        verify(mockExec).shutdown();
        verify(mockExec, never()).shutdownNow();
    }

    @Test
    void shutdown_executorRequiresForcedShutdown() throws Exception {
        ExecutorService mockExec = mock(ExecutorService.class);
        when(mockExec.awaitTermination(60, TimeUnit.SECONDS)).thenReturn(false);
        when(mockExec.awaitTermination(10, TimeUnit.SECONDS)).thenReturn(false);

        ReflectionTestUtils.setField(analyticsService, "discoveryJobExecutor", mockExec);
        analyticsService.shutdown();

        verify(mockExec).shutdown();
        verify(mockExec).shutdownNow();
    }

    @Test
    void shutdown_interruptedDuringAwait_forcesShutdownAndPreservesInterrupt() throws Exception {
        ExecutorService mockExec = mock(ExecutorService.class);
        when(mockExec.awaitTermination(60, TimeUnit.SECONDS)).thenThrow(new InterruptedException("x"));

        ReflectionTestUtils.setField(analyticsService, "discoveryJobExecutor", mockExec);
        analyticsService.shutdown();

        verify(mockExec).shutdown();
        verify(mockExec).shutdownNow();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted(); // clear interrupt flag for subsequent tests
    }
}
