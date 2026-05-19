package org.taniwha.dto;

import org.taniwha.model.FairDataPointSyncResult;

import java.util.List;

public record FairDataPointSyncResponseDTO(
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
    public static FairDataPointSyncResponseDTO from(FairDataPointSyncResult result) {
        return new FairDataPointSyncResponseDTO(
                result.status(),
                result.metadataFilesRead(),
                result.catalogsPublished(),
                result.datasetsPublished(),
                result.distributionsPublished(),
                result.publishedCatalogUris(),
                result.publishedDatasetUris(),
                result.publishedDistributionUris(),
                result.message()
        );
    }

    public static FairDataPointSyncResponseDTO error(String message) {
        return new FairDataPointSyncResponseDTO(
                "ERROR",
                0,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                message
        );
    }
}
