package org.taniwha.service.jobs;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CleaningProcessingJobs {

    public enum State {
        RUNNING,
        DONE,
        ERROR
    }

    @Getter
    @Setter
    public static class JobState {
        private final String jobId;
        private volatile State state = State.RUNNING;
        private final AtomicInteger percent = new AtomicInteger(0);
        private volatile String currentFile = null;
        private volatile String message = null;
        private volatile String result = null;

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

    public void update(String jobId, int percent, String currentFile, String message) {
        JobState s = jobs.get(jobId);
        if (s == null) return;

        s.getPercent().set(Math.max(0, Math.min(100, percent)));
        s.setCurrentFile(currentFile);
        s.setMessage(message);
    }

    public void complete(String jobId, String result) {
        JobState s = jobs.get(jobId);
        if (s == null) return;

        s.setState(State.DONE);
        s.getPercent().set(100);
        s.setCurrentFile(null);
        s.setMessage("Cleaning completed successfully.");
        s.setResult(result);
    }

    public void fail(String jobId, String message) {
        JobState s = jobs.get(jobId);
        if (s == null) return;

        s.setState(State.ERROR);
        s.setMessage(message);
    }

    public void clear(String jobId) {
        jobs.remove(jobId);
    }
}