package org.taniwha.model;

import java.util.List;

public record HarmonizationProvenanceRecord(
        String outputFileName,
        List<String> sourceFileNames,
        String mappingSpecId,
        String generatedAt
) {
}
