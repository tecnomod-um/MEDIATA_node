package org.taniwha.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.taniwha.service.jobs.CleaningProcessingJobs;

@Getter
@Setter
@AllArgsConstructor
public class CleaningStatusDTO {
    private String jobId;
    private CleaningProcessingJobs.State state;
    private int percent;
    private String currentFile;
    private String message;
    private String result;
}