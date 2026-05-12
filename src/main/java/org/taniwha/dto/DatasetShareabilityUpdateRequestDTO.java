package org.taniwha.dto;

public record DatasetShareabilityUpdateRequestDTO(
        String fileName,
        boolean downloadable
) {
}
