package org.taniwha.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.taniwha.dto.SemanticCdeDatasetMatchDTO;
import org.taniwha.dto.SemanticCdeProcessRequestDTO;
import org.taniwha.dto.SemanticCdeProcessResponseDTO;
import org.taniwha.service.semanticcde.SemanticCdeProcessingService;

import java.util.List;

@RestController
@RequestMapping("/api/semantic-cde")
public class SemanticCdeProcessingController {

    private static final Logger logger = LoggerFactory.getLogger(SemanticCdeProcessingController.class);

    private final SemanticCdeProcessingService processingService;

    public SemanticCdeProcessingController(SemanticCdeProcessingService processingService) {
        this.processingService = processingService;
    }

    @GetMapping("/datasets/{fileName:.+}/registry-matches")
    public ResponseEntity<List<SemanticCdeDatasetMatchDTO>> findRegistryMatches(@PathVariable String fileName) {
        return ResponseEntity.ok(processingService.findRegistryMatches(fileName));
    }

    @PostMapping("/process")
    public ResponseEntity<SemanticCdeProcessResponseDTO> process(@Valid @RequestBody SemanticCdeProcessRequestDTO request) {
        logger.info("Semantic-CDE processing requested for dataset={} semanticCde={} mappings={}",
                request.datasetFileName(), request.semanticCdePath(), request.mappingIds());

        SemanticCdeProcessResponseDTO response = processingService.process(request);
        return ResponseEntity
                .status(response.success() ? HttpStatus.OK : HttpStatus.BAD_GATEWAY)
                .body(response);
    }
}
