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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {

    @Mock
    private DataProcessingService dataProcessingService;

    @Mock
    private FileService fileService;

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

    // -----------------------------------------------------------------------
    // isAnyHugeForDiscovery – covers isHugeFile, estimateCsvRows
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // startDiscoveryJob – covers async processing path
    // -----------------------------------------------------------------------

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
