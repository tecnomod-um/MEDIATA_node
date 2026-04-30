package org.taniwha.dto;

import org.junit.jupiter.api.Test;
import org.taniwha.model.FairDataPointSyncResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FairDataPointSyncResponseDTOTest {

    @Test
    void from_mapsSyncResultFields() {
        FairDataPointSyncResponseDTO response = FairDataPointSyncResponseDTO.from(
                new FairDataPointSyncResult(
                        "COMPLETED",
                        3,
                        1,
                        2,
                        2,
                        List.of("catalog-1"),
                        List.of("dataset-1"),
                        List.of("distribution-1"),
                        "ok"
                )
        );

        assertEquals("COMPLETED", response.status());
        assertEquals(3, response.metadataFilesRead());
        assertEquals(1, response.catalogsPublished());
        assertEquals(2, response.datasetsPublished());
        assertEquals(2, response.distributionsPublished());
        assertEquals(List.of("catalog-1"), response.publishedCatalogUris());
        assertEquals(List.of("dataset-1"), response.publishedDatasetUris());
        assertEquals(List.of("distribution-1"), response.publishedDistributionUris());
        assertEquals("ok", response.message());
    }

    @Test
    void error_createsEmptyErrorPayload() {
        FairDataPointSyncResponseDTO response = FairDataPointSyncResponseDTO.error("boom");

        assertEquals("ERROR", response.status());
        assertEquals(0, response.metadataFilesRead());
        assertEquals(0, response.catalogsPublished());
        assertEquals(0, response.datasetsPublished());
        assertEquals(0, response.distributionsPublished());
        assertEquals(List.of(), response.publishedCatalogUris());
        assertEquals(List.of(), response.publishedDatasetUris());
        assertEquals(List.of(), response.publishedDistributionUris());
        assertEquals("boom", response.message());
    }
}
