package org.taniwha.dto;

import java.util.List;

public record DatasetShareabilityUpdateResponseDTO(
        String logicalDatasetId,
        boolean downloadable,
        List<String> affectedFiles,
        String message
) {
}
