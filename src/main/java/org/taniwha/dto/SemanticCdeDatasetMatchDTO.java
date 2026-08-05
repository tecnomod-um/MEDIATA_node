package org.taniwha.dto;

import java.util.List;

public record SemanticCdeDatasetMatchDTO(
        String datasetFileName,
        String title,
        String semanticCdePath,
        List<SemanticCdeConversionDTO> conversions,
        List<String> datasetFields,
        List<String> semanticDescriptions,
        double lexicalScore,
        boolean lexicalMatch
) {
}
