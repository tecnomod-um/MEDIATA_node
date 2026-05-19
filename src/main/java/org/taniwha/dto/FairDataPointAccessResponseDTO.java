package org.taniwha.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class FairDataPointAccessResponseDTO {

    private final String distributionId;
    private final String datasetId;
    private final String fileName;
    private final String message;
    private final String authorizeEndpoint;
    private final String validateEndpoint;
    private final String datasetDownloadEndpoint;
}
