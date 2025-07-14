package org.taniwha.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.DataCleaningOptionsDTO;
import org.taniwha.dto.FileMappingsDTO;
import org.taniwha.service.HarmonizerService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/harmonization")
public class HarmonizationController {

    private static final Logger logger = LoggerFactory.getLogger(HarmonizationController.class);
    private final HarmonizerService harmonizerService;
    private final ObjectMapper objectMapper;

    public HarmonizationController(HarmonizerService harmonizerService, ObjectMapper objectMapper) {
        this.harmonizerService = harmonizerService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/parse")
    public ResponseEntity<String> parseAndClean(@RequestBody FileMappingsDTO dto) {
        try {
            Map<String, List<String>> fileMappings = objectMapper.readValue(
                    dto.getFileMappings(),
                    new TypeReference<>() {
                    }
            );
            String configs = dto.getConfigs();
            DataCleaningOptionsDTO cleanOpts = dto.getCleaningOptions();
            String result = harmonizerService.parseFiles(configs, fileMappings, cleanOpts);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error in file parsing", e);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error processing files: " + e.getMessage());
        }
    }
}
