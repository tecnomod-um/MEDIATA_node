package org.taniwha.service.jobs;

import lombok.Getter;
import lombok.Setter;
import org.taniwha.dto.HarmonizationStatusDTO;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
@Setter
public class HarmonizationJobState {
    private final String jobId;
    private HarmonizationStatusDTO.State state = HarmonizationStatusDTO.State.RUNNING;
    private final AtomicInteger percent = new AtomicInteger(0);
    private String currentDataset = null;
    private String message = null;
    private String result = null;

    HarmonizationJobState(String jobId) {
        this.jobId = jobId;
    }
}
