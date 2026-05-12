package org.taniwha.model;

public record SharedDatasetFile(
        String fingerprint,
        String groupId,
        String fileName
) {
}
