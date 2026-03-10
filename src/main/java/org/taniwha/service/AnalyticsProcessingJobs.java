package org.taniwha.service;

import org.springframework.stereotype.Service;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.dto.ProcessingStatusDTO;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AnalyticsProcessingJobs {

    public static class JobState {
        final String jobId;
        private volatile ProcessingStatusDTO.State state = ProcessingStatusDTO.State.RUNNING;
        final AtomicInteger percent = new AtomicInteger(0);
        volatile String currentFile = null;
        volatile String message = null;
        private volatile List<AnalyticsResponseDTO> results = null;

        JobState(String jobId) { this.jobId = jobId; }

        public ProcessingStatusDTO.State getState() { return state; }
        public List<AnalyticsResponseDTO> getResults() { return results; }
        void setState(ProcessingStatusDTO.State state) { this.state = state; }
        void setResults(List<AnalyticsResponseDTO> results) { this.results = results; }
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

    public ProcessingStatusDTO toDto(JobState s, boolean includeResults) {
        if (s == null) return null;
        return new ProcessingStatusDTO(
                s.jobId,
                s.getState(),
                s.percent.get(),
                s.currentFile,
                s.message,
                includeResults ? s.getResults() : null
        );
    }

    public void clear(String jobId) {
        jobs.remove(jobId);
    }

    public void update(String jobId, int percent, String currentFile) {
        JobState s = jobs.get(jobId);
        if (s == null) return;
        s.currentFile = currentFile;
        s.percent.set(Math.max(0, Math.min(100, percent)));
    }

    public void fail(String jobId, String message) {
        JobState s = jobs.get(jobId);
        if (s == null) return;
        s.setState(ProcessingStatusDTO.State.ERROR);
        s.message = message;
        s.percent.set(Math.max(s.percent.get(), 0));
    }

    public void complete(String jobId, List<AnalyticsResponseDTO> results) {
        JobState s = jobs.get(jobId);
        if (s == null) return;
        s.setState(ProcessingStatusDTO.State.DONE);
        s.setResults(results);
        s.percent.set(100);
    }
}
