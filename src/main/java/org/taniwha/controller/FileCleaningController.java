package org.taniwha.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.DataCleaningOptionsDTO;
import org.taniwha.model.FileCategory;
import org.taniwha.service.DataCleaningService;

@RestController
@RequestMapping("/api/files")
public class FileCleaningController {

    private static final Logger logger = LoggerFactory.getLogger(FileCleaningController.class);

    private final DataCleaningService dataCleaningService;

    public FileCleaningController(DataCleaningService dataCleaningService) {
        this.dataCleaningService = dataCleaningService;
    }

    @PostMapping("/clean")
    public ResponseEntity<Void> cleanFile(
            @RequestParam FileCategory category,
            @RequestParam String name,
            @RequestBody(required = false) DataCleaningOptionsDTO options
    ) {
        logger.debug("Clean file category={} name={} options={}", category, name, options);
        dataCleaningService.cleanInPlace(category, name, options);
        return ResponseEntity.ok().build();
    }
}
