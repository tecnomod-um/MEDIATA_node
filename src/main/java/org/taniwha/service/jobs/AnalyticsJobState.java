package org.taniwha.service.jobs;

import lombok.Getter;
import lombok.Setter;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.dto.ProcessingStatusDTO;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
@Setter
public class AnalyticsJobState {
    private final String jobId;
    private volatile ProcessingStatusDTO.State state = ProcessingStatusDTO.State.RUNNING;
    private final AtomicInteger percent = new AtomicInteger(0);
    private volatile String currentFile = null;
    private volatile String message = null;
    private volatile List<AnalyticsResponseDTO> results = null;
    private volatile Future<?> future;

    AnalyticsJobState(String jobId) {
        this.jobId = jobId;
    }
}
