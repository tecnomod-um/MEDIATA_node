package org.taniwha.service.jobs;

import org.springframework.stereotype.Service;
import org.taniwha.dto.HarmonizationStatusDTO;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class HarmonizationProcessingJobs {

    private final Map<String, HarmonizationJobState> jobs = new ConcurrentHashMap<>();

    public String createJob() {
        String id = UUID.randomUUID().toString();
        jobs.put(id, new HarmonizationJobState(id));
        return id;
    }

    public HarmonizationJobState getJob(String jobId) {
        return jobs.get(jobId);
    }

    public HarmonizationStatusDTO toDto(HarmonizationJobState s, boolean includeResult) {
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
        HarmonizationJobState s = jobs.get(jobId);
        if (s == null) return;
        s.getPercent().set(Math.max(0, Math.min(100, percent)));
        s.setCurrentDataset(currentDataset);
        s.setMessage(message);
    }

    public void complete(String jobId, String result) {
        HarmonizationJobState s = jobs.get(jobId);
        if (s == null) return;
        s.setState(HarmonizationStatusDTO.State.DONE);
        s.getPercent().set(100);
        s.setCurrentDataset(null);
        s.setMessage("Files processed successfully.");
        s.setResult(result);
    }

    public void fail(String jobId, String message) {
        HarmonizationJobState s = jobs.get(jobId);
        if (s == null) return;
        s.setState(HarmonizationStatusDTO.State.ERROR);
        s.setMessage(message);
    }

    public void clear(String jobId) {
        jobs.remove(jobId);
    }
}