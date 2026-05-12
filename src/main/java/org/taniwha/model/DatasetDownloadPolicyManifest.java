package org.taniwha.model;

import java.util.List;

public record DatasetDownloadPolicyManifest(
        List<String> seededSelectors,
        List<SharedDatasetFile> files
) {
}
