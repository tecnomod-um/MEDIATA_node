package org.taniwha.service.jobs;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Service;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.dto.ProcessingStatusDTO;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AnalyticsProcessingJobs {

    @Getter
    @Setter
    public static class JobState {
        private final String jobId;
        private volatile ProcessingStatusDTO.State state = ProcessingStatusDTO.State.RUNNING;
        private final AtomicInteger percent = new AtomicInteger(0);
        private volatile String currentFile = null;
        private volatile String message = null;
        private volatile List<AnalyticsResponseDTO> results = null;
        private volatile Future<?> future;

        JobState(String jobId) {
            this.jobId = jobId;
        }
    }

    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

    public String createJob() {
        String id = UUID.randomUUID().toString();
        jobs.put(id, new JobState(id));
        return id;
    }

    public JobState getJob(String jobId) {
        return jobs.get(jobId);
    }

    public void attachFuture(String jobId, Future<?> future) {
        JobState s = jobs.get(jobId);
        if (s == null) return;
        s.setFuture(future);
    }

    public void cancel(String jobId, String message) {
        JobState s = jobs.get(jobId);
        if (s == null) return;

        if (s.getState() == ProcessingStatusDTO.State.DONE
                || s.getState() == ProcessingStatusDTO.State.ERROR
                || s.getState() == ProcessingStatusDTO.State.CANCELED) {
            return;
        }

        Future<?> future = s.getFuture();
        if (future != null) {
            future.cancel(true);
        }

        s.setState(ProcessingStatusDTO.State.CANCELED);
        s.setMessage(message != null ? message : "Job canceled");
    }

    public boolean isCanceled(String jobId) {
        JobState s = jobs.get(jobId);
        if (s == null) return true;
        return s.getState() == ProcessingStatusDTO.State.CANCELED;
    }

    public ProcessingStatusDTO toDto(JobState s, boolean includeResults) {
        if (s == null) return null;
        return new ProcessingStatusDTO(
                s.getJobId(),
                s.getState(),
                s.getPercent().get(),
                s.getCurrentFile(),
                s.getMessage(),
                includeResults ? s.getResults() : null
        );
    }

    public void clear(String jobId) {
        jobs.remove(jobId);
    }

    public void update(String jobId, int percent, String currentFile) {
        JobState s = jobs.get(jobId);
        if (s == null) return;
        if (s.getState() == ProcessingStatusDTO.State.CANCELED) return;

        s.setCurrentFile(currentFile);
        s.getPercent().set(Math.max(0, Math.min(100, percent)));
    }

    public void fail(String jobId, String message) {
        JobState s = jobs.get(jobId);
        if (s == null) return;
        if (s.getState() == ProcessingStatusDTO.State.CANCELED) return;

        s.setState(ProcessingStatusDTO.State.ERROR);
        s.setMessage(message);
        s.getPercent().set(Math.max(s.getPercent().get(), 0));
    }

    public void complete(String jobId, List<AnalyticsResponseDTO> results) {
        JobState s = jobs.get(jobId);
        if (s == null) return;
        if (s.getState() == ProcessingStatusDTO.State.CANCELED) return;

        s.setState(ProcessingStatusDTO.State.DONE);
        s.setResults(results);
        s.getPercent().set(100);
        s.setCurrentFile(null);
    }
}