package org.taniwha.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.security.FileFilter;
import org.taniwha.service.AnalyticsService;

import java.util.Map;
import java.util.concurrent.ExecutionException;

// Exposes uris to operate with aggregated data
@RestController
@RequestMapping("/api/data")
public class AnalyticsController {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsController.class);
    private static final String UNSUPPORTED_FILE_MESSAGE = "The file sent is not supported";
    private final AnalyticsService analyticsService;
    private final FileFilter fileFilter;

    public AnalyticsController(AnalyticsService analyticsService, FileFilter fileFilter) {
        this.analyticsService = analyticsService;
        this.fileFilter = fileFilter;
    }

    @PostMapping("/process")
    public ResponseEntity<AnalyticsResponseDTO> processAnalytics(@RequestParam("file") MultipartFile file) {
        logger.debug("File processing request: {}", file.getOriginalFilename());

        if (fileFilter.isFileInvalid(file))
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(new AnalyticsResponseDTO(UNSUPPORTED_FILE_MESSAGE));
        try {
            AnalyticsResponseDTO response = analyticsService.processAnalytics(file).get();
            logger.info("File processed: {}", file.getOriginalFilename());
            return ResponseEntity.ok(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Thread was interrupted while processing file: {}", file.getOriginalFilename(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new AnalyticsResponseDTO("Thread was interrupted while processing file."));
        } catch (ExecutionException e) {
            logger.error("Error processing file: {}", file.getOriginalFilename(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new AnalyticsResponseDTO("Error processing file" + e));
        }
    }

    @PostMapping("/reprocess")
    public ResponseEntity<AnalyticsResponseDTO> recalculateFeature(@RequestParam("file") MultipartFile file, @RequestParam("featureName") String featureName, @RequestParam("featureType") String featureType) {
        logger.debug("File reprocessing request: {} as type {} for file: {}", featureName, featureType, file.getOriginalFilename());

        if (fileFilter.isFileInvalid(file))
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(new AnalyticsResponseDTO(UNSUPPORTED_FILE_MESSAGE));
        if (!featureType.equalsIgnoreCase("continuous") && !featureType.equalsIgnoreCase("categorical")) {
            logger.warn("Invalid feature type provided: {}", featureType);
            return ResponseEntity.badRequest().body(new AnalyticsResponseDTO("Invalid feature type"));
        }
        try {
            AnalyticsResponseDTO response = analyticsService.recalculateFeatureAsType(file, featureName, featureType).get();
            logger.info("Feature recalculated for file: {}", file.getOriginalFilename());
            return ResponseEntity.ok(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Thread was interrupted while reprocessing field in file: {}", file.getOriginalFilename(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new AnalyticsResponseDTO("Thread was interrupted while filtering data."));
        } catch (ExecutionException e) {
            logger.error("Error recalculating feature for file: {}", file.getOriginalFilename(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new AnalyticsResponseDTO("Error processing file" + e));
        }
    }

    @PostMapping("/filter")
    public ResponseEntity<AnalyticsResponseDTO> filterData(@RequestParam("file") MultipartFile file, @RequestParam("filters") String filtersJson) {
        logger.debug("Feature filtering request for file: {}", file.getOriginalFilename());
        if (fileFilter.isFileInvalid(file))
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(new AnalyticsResponseDTO(UNSUPPORTED_FILE_MESSAGE));
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> filters = objectMapper.readValue(filtersJson, new TypeReference<>() {
            });
            AnalyticsResponseDTO response = analyticsService.filterData(file, filters).get();
            logger.info("Features filtered in file: {}", file.getOriginalFilename());
            return ResponseEntity.ok(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Thread was interrupted while filtering data for file: {}", file.getOriginalFilename(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new AnalyticsResponseDTO("Thread was interrupted while filtering data."));
        } catch (JsonProcessingException | ExecutionException e) {
            logger.error("Error filtering data for file: {}", file.getOriginalFilename(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new AnalyticsResponseDTO("Error filtering data" + e));
        }
    }
}
