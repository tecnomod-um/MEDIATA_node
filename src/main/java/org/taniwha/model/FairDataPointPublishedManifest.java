package org.taniwha.model;

import java.util.List;

public record FairDataPointPublishedManifest(
        List<String> catalogUris,
        List<String> datasetUris,
        List<String> distributionUris
) {
}
