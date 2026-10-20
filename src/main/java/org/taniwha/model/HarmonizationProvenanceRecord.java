package org.taniwha.model;

import java.util.List;

public record HarmonizationProvenanceRecord(
        String activityId,
        String outputFileName,
        List<String> sourceFileNames,
        String mappingSpecId,
        String generatedAt
) {
    public HarmonizationProvenanceRecord(String outputFileName,
                                         List<String> sourceFileNames,
                                         String mappingSpecId,
                                         String generatedAt) {
        this(null, outputFileName, sourceFileNames, mappingSpecId, generatedAt);
    }
}
