package org.taniwha.service.jobs;

import org.springframework.stereotype.Service;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.dto.ProcessingStatusDTO;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

@Service
public class AnalyticsProcessingJobs {

    private final Map<String, AnalyticsJobState> jobs = new ConcurrentHashMap<>();

    public String createJob() {
        String id = UUID.randomUUID().toString();
        jobs.put(id, new AnalyticsJobState(id));
        return id;
    }

    public AnalyticsJobState getJob(String jobId) {
        return jobs.get(jobId);
    }

    public synchronized void attachFuture(String jobId, Future<?> future) {
        AnalyticsJobState s = jobs.get(jobId);
        if (s == null) return;
        s.setFuture(future);
        if (s.getState() == ProcessingStatusDTO.State.CANCELED) future.cancel(true);
    }

    public synchronized void cancel(String jobId, String message) {
        AnalyticsJobState s = jobs.get(jobId);
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
        AnalyticsJobState s = jobs.get(jobId);
        if (s == null) return true;
        return s.getState() == ProcessingStatusDTO.State.CANCELED;
    }

    public ProcessingStatusDTO toDto(AnalyticsJobState s, boolean includeResults) {
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

    public synchronized void clear(String jobId) {
        jobs.remove(jobId);
    }

    public synchronized void update(String jobId, int percent, String currentFile) {
        AnalyticsJobState s = jobs.get(jobId);
        if (s == null) return;
        if (s.getState() == ProcessingStatusDTO.State.CANCELED) return;

        s.setCurrentFile(currentFile);
        s.getPercent().set(Math.max(0, Math.min(100, percent)));
    }

    public synchronized void fail(String jobId, String message) {
        AnalyticsJobState s = jobs.get(jobId);
        if (s == null) return;
        if (s.getState() == ProcessingStatusDTO.State.CANCELED) return;

        s.setState(ProcessingStatusDTO.State.ERROR);
        s.setMessage(message);
        s.getPercent().set(Math.max(s.getPercent().get(), 0));
    }

    public synchronized void complete(String jobId, List<AnalyticsResponseDTO> results) {
        AnalyticsJobState s = jobs.get(jobId);
        if (s == null) return;
        if (s.getState() == ProcessingStatusDTO.State.CANCELED) return;

        s.setResults(results);
        s.setState(ProcessingStatusDTO.State.DONE);
        s.getPercent().set(100);
        s.setCurrentFile(null);
    }
}