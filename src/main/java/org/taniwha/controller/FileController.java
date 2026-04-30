package org.taniwha.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.MediaTypeFactory;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.FileInfoDto;
import org.taniwha.model.FileCategory;
import org.taniwha.model.NodeMetadata;
import org.taniwha.service.FileService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final String ERROR_MSG = "Error listing files";

    private static final Logger logger = LoggerFactory.getLogger(FileController.class);

    private final FileService fileService;

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

    @GetMapping("/datasets/{fileName}")
    public ResponseEntity<byte[]> getDatasetFile(@PathVariable String fileName) {
        logger.debug("Request to fetch dataset file: {}", fileName);

        try {
            Path path = fileService.resolveDatasetFilePath(fileName);
            byte[] fileContent = Files.readAllBytes(path);
            MediaType mediaType = MediaTypeFactory.getMediaType(fileName)
                    .orElse(MediaType.APPLICATION_OCTET_STREAM);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(mediaType);
            headers.setContentDisposition(
                    ContentDisposition.inline()
                            .filename(fileName, StandardCharsets.UTF_8)
                            .build()
            );

            logger.info("Fetched dataset file: {}", fileName);
            return new ResponseEntity<>(fileContent, headers, HttpStatus.OK);
        } catch (Exception e) {
            logger.error("Error fetching dataset file: {}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("Error fetching dataset file: " + fileName).getBytes(StandardCharsets.UTF_8));
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
            fileService.saveDatasetElements(fileName, csvData);
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
            String fileContent = Files.readString(fileService.resolveElementFilePath(fileName));
            logger.info("Fetched content of element file: {}", fileName);
            return ResponseEntity.ok(fileContent);
        } catch (Exception e) {
            logger.error("Error fetching element file: {}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching element file: " + fileName);
        }
    }

    @GetMapping(value = "/metadata/dcat", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getRawDcatMetadata() {
        logger.debug("Request to fetch raw DCAT metadata");

        String raw = fileService.getRawNodeMetadata();

        if (raw == null) {
            return ResponseEntity.notFound().build();
        }

        String fileName = fileService.getRawNodeMetadataFileName();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));

        if (fileName != null) {
            headers.setContentDisposition(
                    ContentDisposition.inline()
                            .filename(fileName, StandardCharsets.UTF_8)
                            .build()
            );
        }

        return new ResponseEntity<>(raw, headers, HttpStatus.OK);
    }

    @GetMapping(value = "/metadata/dcat/formatted", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NodeMetadata> getFormattedDcatMetadata() {
        logger.debug("Request to fetch formatted DCAT metadata");

        NodeMetadata metadata = fileService.parseNodeMetadata();

        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(metadata);
    }

    @GetMapping
    public ResponseEntity<List<FileInfoDto>> listFiles(@RequestParam FileCategory category) {
        logger.debug("List files for category={}", category);
        return ResponseEntity.ok(fileService.listFilesWithInfo(category));
    }

    @PostMapping("/rename")
    public ResponseEntity<Void> renameFile(
            @RequestParam FileCategory category,
            @RequestParam String from,
            @RequestParam String to
    ) {
        logger.debug("Rename file category={} from={} to={}", category, from, to);

        fileService.renameFile(category, from, to);

        return ResponseEntity.ok().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteFile(
            @RequestParam FileCategory category,
            @RequestParam String name
    ) {
        logger.debug("Delete file category={} name={}", category, name);

        fileService.deleteFile(category, name);

        return ResponseEntity.ok().build();
    }
}
