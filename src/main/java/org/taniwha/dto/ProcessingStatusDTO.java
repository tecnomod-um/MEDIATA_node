package org.taniwha.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.taniwha.dto.AnalyticsResponseDTO;

import java.util.List;

@Setter @Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingStatusDTO {
    public enum State { RUNNING, DONE, ERROR }

    private String jobId;
    private State state;
    private int percent;
    private String currentFile;
    private String message;
    private List<AnalyticsResponseDTO> results;
}
