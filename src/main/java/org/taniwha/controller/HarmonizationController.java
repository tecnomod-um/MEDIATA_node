package org.taniwha.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.taniwha.service.HarmonizerService;

@RestController
@RequestMapping("/api/harmonization")
public class HarmonizationController {

    private static final Logger logger = LoggerFactory.getLogger(HarmonizationController.class);
    private final HarmonizerService harmonizerService;

    public HarmonizationController(HarmonizerService harmonizerService) {
        this.harmonizerService = harmonizerService;
    }

    @PostMapping("/mock")
    public ResponseEntity<String> uploadMockFile(@RequestParam("file") MultipartFile file) {
        try {
            String result = harmonizerService.saveFile(file);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error saving file", e);
            return ResponseEntity.status(500).body("Error saving file: " + e.getMessage());
        }
    }
}
