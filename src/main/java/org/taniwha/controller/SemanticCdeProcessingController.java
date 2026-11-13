package org.taniwha.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Semantic CDE", description = "Match datasets to registry definitions and generate semantic CDE artifacts.")
public class SemanticCdeProcessingController {

    private static final Logger logger = LoggerFactory.getLogger(SemanticCdeProcessingController.class);

    private final SemanticCdeProcessingService processingService;

    public SemanticCdeProcessingController(SemanticCdeProcessingService processingService) {
        this.processingService = processingService;
    }

    @Operation(summary = "Find registry matches already associated with a dataset")
    @GetMapping("/datasets/{fileName:.+}/registry-matches")
    public ResponseEntity<List<SemanticCdeDatasetMatchDTO>> findRegistryMatches(@PathVariable String fileName) {
        return ResponseEntity.ok(processingService.findRegistryMatches(fileName));
    }

    @Operation(summary = "Generate semantic CDE output for a dataset")
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
