package org.taniwha.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.service.FileService;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/files")
public class FileController {
    private static final String ERROR_MSG = "Error listing files";
    private final FileService fileService;

    private static final Logger logger = LoggerFactory.getLogger(FileController.class);

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @GetMapping("/datasets")
    public ResponseEntity<List<String>> listDatasetFiles() {
        logger.debug("Request to list files in datasets folder");
        try {
            List<String> files = fileService.listDatasetFiles();
            logger.info("Retrieved {} files from datasets folder", files.size());
            return ResponseEntity.ok(files);
        } catch (Exception e) {
            logger.error("Error listing files in datasets folder", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(List.of(ERROR_MSG));
        }
    }

    @GetMapping("/mapped_datasets")
    public ResponseEntity<List<String>> listMappedDatasetFiles() {
        logger.debug("Request to list files in mapped_datasets folder");
        try {
            List<String> files = fileService.listMappedDatasetFiles();
            logger.info("Retrieved {} files from mapped_datasets folder", files.size());
            return ResponseEntity.ok(files);
        } catch (Exception e) {
            logger.error("Error listing files in mapped_datasets folder", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(List.of(ERROR_MSG));
        }
    }

    @GetMapping("/fhir_mappings")
    public ResponseEntity<List<String>> listFhirMappingFiles() {
        logger.debug("Request to list files in fhir_mappings folder");
        try {
            List<String> files = fileService.listFhirMappingFiles();
            logger.info("Retrieved {} files from fhir_mappings folder", files.size());
            return ResponseEntity.ok(files);
        } catch (Exception e) {
            logger.error("Error listing files in fhir_mappings folder", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(List.of(ERROR_MSG));
        }
    }

    @GetMapping("/dataset_elements")
    public ResponseEntity<List<String>> listDatasetElements() {
        logger.debug("Request to list files in dataset_elements folder");
        try {
            List<String> files = fileService.listElementFiles();
            logger.info("Retrieved {} files from dataset_elements folder", files.size());
            return ResponseEntity.ok(files);
        } catch (Exception e) {
            logger.error("Error listing files in dataset_elements folder", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(List.of(ERROR_MSG));
        }
    }

    @PostMapping("/save_dataset_elements")
    public ResponseEntity<String> saveDatasetElements(
            @RequestParam("fileName") String fileName,
            @RequestBody String csvData
    ) {
        logger.debug("Request to save dataset elements: {}", fileName);
        try {
            String filePath = fileService.saveDatasetElements(fileName, csvData);
            logger.info("Dataset elements saved to {}", filePath);
            return ResponseEntity.ok("Dataset elements saved successfully.");
        } catch (Exception e) {
            logger.error("Error saving dataset elements", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error saving dataset elements.");
        }
    }

    @GetMapping("/dataset_elements/{fileName}")
    public ResponseEntity<String> getElementFile(@PathVariable String fileName) {
        logger.debug("Request to fetch element file: {}", fileName);
        try {
            String filePath = fileService.getElementFilePath(fileName);
            String fileContent = Files.readString(Paths.get(filePath));
            logger.info("Fetched content of element file: {}", fileName);
            return ResponseEntity.ok(fileContent);
        } catch (Exception e) {
            logger.error("Error fetching element file: {}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching element file: " + fileName);
        }
    }
}
