package org.taniwha.model;

import java.util.List;

public record FairDataPointSyncResult(
        String status,
        int metadataFilesRead,
        int catalogsPublished,
        int datasetsPublished,
        int distributionsPublished,
        List<String> publishedCatalogUris,
        List<String> publishedDatasetUris,
        List<String> publishedDistributionUris,
        String message
) {
}
