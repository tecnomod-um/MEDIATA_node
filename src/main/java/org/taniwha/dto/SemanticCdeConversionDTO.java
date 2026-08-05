package org.taniwha.dto;

import java.util.List;

public record SemanticCdeConversionDTO(
        String id,
        String label,
        String description,
        String source,
        String target,
        String outputSuffix,
        List<String> mappingIds
) {
    public SemanticCdeConversionDTO {
        mappingIds = mappingIds == null || mappingIds.isEmpty()
                ? List.of(id)
                : List.copyOf(mappingIds);
    }
}
