package org.taniwha.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.dto.FileNamesDTO;
import org.taniwha.dto.MultiFileFilterRequest;
import org.taniwha.service.AnalyticsService;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

// Exposes uris to operate with aggregated data
@RestController
@RequestMapping("/api/data")
public class AnalyticsController {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsController.class);
    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @PostMapping("/processList")
    public ResponseEntity<List<AnalyticsResponseDTO>> processList(@RequestBody FileNamesDTO dto) {
        try {
            List<AnalyticsResponseDTO> resultList = analyticsService.processDatasetsOnDisk(dto.getFileNames());
            return ResponseEntity.ok(resultList);
        } catch (Exception e) {
            logger.error("Error processing file list", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(List.of(new AnalyticsResponseDTO("Error processing files: " + e.getMessage())));
        }
    }

    @PostMapping("/reprocessList")
    public ResponseEntity<AnalyticsResponseDTO> recalculateFeatureList(@RequestParam("fileName") String fileName, @RequestParam("featureName") String featureName, @RequestParam("featureType") String featureType) {
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
        logger.debug("Received multiple-file filter request with {} entries",
                payload.getMultipleFileFilters().size());

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
