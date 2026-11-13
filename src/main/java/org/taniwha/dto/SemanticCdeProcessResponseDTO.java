package org.taniwha.dto;

import java.util.List;

public record SemanticCdeProcessResponseDTO(
        boolean success,
        String datasetFileName,
        String semanticCdePath,
        List<String> mappingIds,
        List<String> outputFiles,
        String message,
        String processorLog
) {
}
