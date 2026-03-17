package org.taniwha.service.jobs;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Service;
import org.taniwha.dto.HarmonizationStatusDTO;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class HarmonizationProcessingJobs {

    @Getter
    @Setter
    public static class JobState {
        private final String jobId;
        private HarmonizationStatusDTO.State state = HarmonizationStatusDTO.State.RUNNING;
        private final AtomicInteger percent = new AtomicInteger(0);
        private String currentDataset = null;
        private String message = null;
        private String result = null;

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

    public HarmonizationStatusDTO toDto(JobState s, boolean includeResult) {
        if (s == null) return null;
        return new HarmonizationStatusDTO(
                s.getJobId(),
                s.getState(),
                s.getPercent().get(),
                s.getCurrentDataset(),
                s.getMessage(),
                includeResult ? s.getResult() : null
        );
    }

    public void update(String jobId, int percent, String currentDataset, String message) {
        JobState s = jobs.get(jobId);
        if (s == null) return;
        s.getPercent().set(Math.max(0, Math.min(100, percent)));
        s.setCurrentDataset(currentDataset);
        s.setMessage(message);
    }

    public void complete(String jobId, String result) {
        JobState s = jobs.get(jobId);
        if (s == null) return;
        s.setState(HarmonizationStatusDTO.State.DONE);
        s.getPercent().set(100);
        s.setCurrentDataset(null);
        s.setMessage("Files processed successfully.");
        s.setResult(result);
    }

    public void fail(String jobId, String message) {
        JobState s = jobs.get(jobId);
        if (s == null) return;
        s.setState(HarmonizationStatusDTO.State.ERROR);
        s.setMessage(message);
    }

    public void clear(String jobId) {
        jobs.remove(jobId);
    }
}