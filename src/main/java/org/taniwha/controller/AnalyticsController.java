package org.taniwha.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.*;
import org.taniwha.service.AnalyticsProcessingJobs;
import org.taniwha.service.AnalyticsService;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/data")
public class AnalyticsController {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsController.class);

    private final AnalyticsService analyticsService;
    private final AnalyticsProcessingJobs jobs;

    public AnalyticsController(AnalyticsService analyticsService, AnalyticsProcessingJobs jobs) {
        this.analyticsService = analyticsService;
        this.jobs = jobs;
    }

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

        } catch (Exception e) {
            logger.error("Error processing file list", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(List.of(new AnalyticsResponseDTO("Error processing files: " + e.getMessage())));
        }
    }

    @GetMapping("/processList/status/{jobId}")
    public ResponseEntity<ProcessingStatusDTO> processListStatus(
            @PathVariable String jobId
    ) {
        AnalyticsProcessingJobs.JobState s = jobs.getJob(jobId);
        if (s == null) return ResponseEntity.notFound().build();
        // Do NOT include results in polling response
        return ResponseEntity.ok(jobs.toDto(s, false));
    }

    @GetMapping("/processList/result/{jobId}")
    public ResponseEntity<List<AnalyticsResponseDTO>> processListResult(@PathVariable String jobId) {
        AnalyticsProcessingJobs.JobState s = jobs.getJob(jobId);
        if (s == null) return ResponseEntity.notFound().build();

        if (s.getState() != ProcessingStatusDTO.State.DONE)
            return ResponseEntity.status(HttpStatus.CONFLICT).build();

        List<AnalyticsResponseDTO> out = s.getResults() == null ? List.of() : s.getResults();
        jobs.clear(jobId);

        return ResponseEntity.ok(out);
    }

    @PostMapping("/reprocessList")
    public ResponseEntity<AnalyticsResponseDTO> recalculateFeatureList(
            @RequestParam("fileName") String fileName,
            @RequestParam("featureName") String featureName,
            @RequestParam("featureType") String featureType
    ) {
        logger.debug("File reprocessing request: {} as type {} for file: {}", featureName, featureType, fileName);
        if (!featureType.equalsIgnoreCase("continuous") && !featureType.equalsIgnoreCase("categorical")) {
            logger.warn("Invalid feature type provided: {}", featureType);
            return ResponseEntity.badRequest().body(new AnalyticsResponseDTO("Invalid feature type"));
        }
        try {
            AnalyticsResponseDTO response = analyticsService.recalculateFeatureAsTypeFromDisk(fileName, featureName, featureType).get();
            logger.info("Feature recalculated for file: {}", fileName);
            return ResponseEntity.ok(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Thread was interrupted while reprocessing field in file: {}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new AnalyticsResponseDTO("Thread was interrupted while processing file."));
        } catch (ExecutionException e) {
            logger.error("Error recalculating feature for file: {}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new AnalyticsResponseDTO("Error processing file: " + e.getMessage()));
        }
    }

    @PostMapping("/filterByNameList")
    public ResponseEntity<List<AnalyticsResponseDTO>> filterByNameList(@RequestBody MultiFileFilterRequest payload) {
        logger.debug("Received multiple-file filter request with {} entries", payload.getMultipleFileFilters().size());
        try {
            List<AnalyticsResponseDTO> filteredList = analyticsService.filterMultipleFilesByName(payload.getMultipleFileFilters());
            return ResponseEntity.ok(filteredList);
        } catch (Exception e) {
            logger.error("Error filtering multiple files", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonList(new AnalyticsResponseDTO("Error filtering multiple files: " + e.getMessage())));
        }
    }
}
