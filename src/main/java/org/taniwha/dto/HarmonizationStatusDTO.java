package org.taniwha.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class HarmonizationStatusDTO {

    public enum State {
        RUNNING,
        DONE,
        ERROR
    }

    private String jobId;
    private State state;
    private int percent;
    private String currentDataset;
    private String message;
    private String result;
}