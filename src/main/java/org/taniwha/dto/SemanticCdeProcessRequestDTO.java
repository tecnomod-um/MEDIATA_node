package org.taniwha.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SemanticCdeProcessRequestDTO(
        @NotBlank String datasetFileName,
        @NotBlank String semanticCdePath,
        @NotEmpty List<@NotBlank String> mappingIds,
        String outputFileName
) {
}
