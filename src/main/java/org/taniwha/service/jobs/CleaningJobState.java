package org.taniwha.service.jobs;

import lombok.Getter;
import lombok.Setter;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
@Setter
public class CleaningJobState {
    private final String jobId;
    private volatile CleaningProcessingJobs.State state = CleaningProcessingJobs.State.RUNNING;
    private final AtomicInteger percent = new AtomicInteger(0);
    private volatile String currentFile = null;
    private volatile String message = null;
    private volatile String result = null;

    CleaningJobState(String jobId) {
        this.jobId = jobId;
    }
}
