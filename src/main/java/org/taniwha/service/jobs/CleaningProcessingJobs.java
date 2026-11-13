package org.taniwha.service.jobs;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CleaningProcessingJobs {

    public enum State {
        RUNNING,
        DONE,
        ERROR
    }

    private final Map<String, CleaningJobState> jobs = new ConcurrentHashMap<>();

    public String createJob() {
        String id = UUID.randomUUID().toString();
        jobs.put(id, new CleaningJobState(id));
        return id;
    }

    public CleaningJobState getJob(String jobId) {
        return jobs.get(jobId);
    }

    public void update(String jobId, int percent, String currentFile, String message) {
        CleaningJobState s = jobs.get(jobId);
        if (s == null) return;

        s.getPercent().set(Math.max(0, Math.min(100, percent)));
        s.setCurrentFile(currentFile);
        s.setMessage(message);
    }

    public void complete(String jobId, String result) {
        CleaningJobState s = jobs.get(jobId);
        if (s == null) return;

        s.setState(State.DONE);
        s.getPercent().set(100);
        s.setCurrentFile(null);
        s.setMessage("Cleaning completed successfully.");
        s.setResult(result);
    }

    public void fail(String jobId, String message) {
        CleaningJobState s = jobs.get(jobId);
        if (s == null) return;

        s.setState(State.ERROR);
        s.setMessage(message);
    }

    public void clear(String jobId) {
        jobs.remove(jobId);
    }
}