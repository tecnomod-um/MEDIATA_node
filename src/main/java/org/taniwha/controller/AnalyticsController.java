package org.taniwha.controller;

import org.taniwha.service.jobs.AnalyticsJobState;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.*;
import org.taniwha.service.jobs.AnalyticsProcessingJobs;
import org.taniwha.service.AnalyticsService;
import org.taniwha.service.DatasetElementExtractionService;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/data")
@Tag(name = "Dataset analytics", description = "Profile datasets, inspect columns, and run filtered discovery jobs.")
public class AnalyticsController {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsController.class);

    private final AnalyticsService analyticsService;
    private final AnalyticsProcessingJobs jobs;
    private final DatasetElementExtractionService datasetElementExtractionService;

    public AnalyticsController(
            AnalyticsService analyticsService,
            AnalyticsProcessingJobs jobs,
            DatasetElementExtractionService datasetElementExtractionService
    ) {
        this.analyticsService = analyticsService;
        this.jobs = jobs;
        this.datasetElementExtractionService = datasetElementExtractionService;
    }

    @Operation(summary = "Start dataset profiling")
    @PostMapping("/processList")
    public ResponseEntity<Object> processList(@RequestBody FileNamesDTO dto) {
        try {
            List<String> fileNames = dto.getFileNames();
            boolean huge = analyticsService.isAnyHugeForDiscovery(fileNames);

            if (!huge) {
                List<AnalyticsResponseDTO> resultList = analyticsService.processDatasetsOnDisk(fileNames);
                return ResponseEntity.ok(resultList);
            }

            String jobId = jobs.createJob();
            analyticsService.startDiscoveryJob(jobId, fileNames);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ProcessingStartDTO(jobId, true));

        } catch (java.util.concurrent.RejectedExecutionException busy) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(List.of(new AnalyticsResponseDTO("The node is busy. Please retry shortly.")));
        } catch (Exception e) {
            logger.warn("Error processing file list");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(List.of(new AnalyticsResponseDTO("Error processing statistics.")));
        }
    }

    @Operation(summary = "Extract dataset element metadata")
    @PostMapping("/extractElements")
    public ResponseEntity<List<DatasetElementsDTO>> extractElements(@RequestBody FileNamesDTO dto) {
        try {
            List<String> fileNames = dto == null || dto.getFileNames() == null ? List.of() : dto.getFileNames();
            return ResponseEntity.ok(datasetElementExtractionService.extractDatasetElements(fileNames));
        } catch (Exception e) {
            logger.warn("Error extracting dataset elements");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(List.of());
        }
    }

    @Operation(summary = "Get profiling job status")
    @GetMapping("/processList/status/{jobId}")
    public ResponseEntity<ProcessingStatusDTO> processListStatus(
            @PathVariable String jobId
    ) {
        AnalyticsJobState s = jobs.getJob(jobId);
        if (s == null) return ResponseEntity.notFound().build();
        // Do NOT include results in polling response
        return ResponseEntity.ok(jobs.toDto(s, false));
    }

    @Operation(summary = "Cancel a profiling job")
    @PostMapping("/processList/cancel/{jobId}")
    public ResponseEntity<ProcessingStatusDTO> cancelProcessList(@PathVariable String jobId) {
        AnalyticsJobState s = jobs.getJob(jobId);
        if (s == null) {
            logger.warn("Received cancel request for unknown discovery jobId={}", jobId);
            return ResponseEntity.notFound().build();
        }

        logger.info("Received cancel request for discovery jobId={}", jobId);
        jobs.cancel(jobId, "Discovery canceled because the user left the page");
        logger.info("Discovery job marked as canceled jobId={}", jobId);

        return ResponseEntity.ok(jobs.toDto(jobs.getJob(jobId), false));
    }

    @Operation(summary = "Get profiling job results")
    @GetMapping("/processList/result/{jobId}")
    public ResponseEntity<List<AnalyticsResponseDTO>> processListResult(@PathVariable String jobId) {
        AnalyticsJobState s = jobs.getJob(jobId);
        if (s == null) return ResponseEntity.notFound().build();

        if (s.getState() == ProcessingStatusDTO.State.CANCELED) {
            jobs.clear(jobId);
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        if (s.getState() != ProcessingStatusDTO.State.DONE) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        List<AnalyticsResponseDTO> out = s.getResults() == null ? List.of() : s.getResults();
        jobs.clear(jobId);
        return ResponseEntity.ok(out);
    }

    @Operation(summary = "Profile records matching a feature filter")
    @PostMapping("/filterByNameList")
    public ResponseEntity<Object> filterByNameList(@RequestBody MultiFileFilterRequest payload) {
        logger.debug("Received multiple-file filter request with {} entries", payload.getMultipleFileFilters().size());
        try {
            if (analyticsService.isAnyHugeForDiscovery(payload.getMultipleFileFilters().stream().map(FileFilters::getFileName).toList())) {
                String id = jobs.createJob();
                analyticsService.startFilteredDiscoveryJob(id, payload.getMultipleFileFilters());
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ProcessingStartDTO(id, true));
            }
            List<AnalyticsResponseDTO> filteredList = analyticsService.filterMultipleFilesByName(payload.getMultipleFileFilters());
            return ResponseEntity.ok(filteredList);
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(List.of(new AnalyticsResponseDTO("The node is busy. Please retry shortly.")));
        } catch (Exception e) {
            logger.warn("Error filtering multiple files");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonList(new AnalyticsResponseDTO("Error processing statistics.")));
        }
    }
}
