package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.dto.ProcessingStatusDTO;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AnalyticsProcessingJobsTest {

    private AnalyticsProcessingJobs jobs;

    @BeforeEach
    void setUp() {
        jobs = new AnalyticsProcessingJobs();
    }

    @Test
    void createJob_shouldGenerateUniqueJobId() {
        String jobId1 = jobs.createJob();
        String jobId2 = jobs.createJob();

        assertThat(jobId1).isNotNull();
        assertThat(jobId2).isNotNull();
        assertThat(jobId1).isNotEqualTo(jobId2);
    }

    @Test
    void getJob_withValidJobId_shouldReturnJobState() {
        String jobId = jobs.createJob();
        
        AnalyticsProcessingJobs.JobState state = jobs.getJob(jobId);
        
        assertThat(state).isNotNull();
        assertThat(state.jobId).isEqualTo(jobId);
        assertThat(state.getState()).isEqualTo(ProcessingStatusDTO.State.RUNNING);
        assertThat(state.percent.get()).isZero();
    }

    @Test
    void getJob_withInvalidJobId_shouldReturnNull() {
        AnalyticsProcessingJobs.JobState state = jobs.getJob("non-existent");
        
        assertThat(state).isNull();
    }

    @Test
    void update_shouldUpdateProgressAndCurrentFile() {
        String jobId = jobs.createJob();
        
        jobs.update(jobId, 50, "test-file.csv");
        
        AnalyticsProcessingJobs.JobState state = jobs.getJob(jobId);
        assertThat(state.percent.get()).isEqualTo(50);
        assertThat(state.currentFile).isEqualTo("test-file.csv");
    }

    @Test
    void update_shouldClampPercentageTo0_100Range() {
        String jobId = jobs.createJob();
        
        jobs.update(jobId, 150, "file.csv");
        assertThat(jobs.getJob(jobId).percent.get()).isEqualTo(100);
        
        jobs.update(jobId, -50, "file.csv");
        assertThat(jobs.getJob(jobId).percent.get()).isZero();
    }

    @Test
    void update_withInvalidJobId_shouldNotThrowException() {
        assertThatCode(() -> jobs.update("non-existent", 50, "file.csv"))
                .doesNotThrowAnyException();
    }

    @Test
    void fail_shouldSetErrorState() {
        String jobId = jobs.createJob();
        jobs.update(jobId, 50, "file.csv");
        
        jobs.fail(jobId, "Test error message");
        
        AnalyticsProcessingJobs.JobState state = jobs.getJob(jobId);
        assertThat(state.getState()).isEqualTo(ProcessingStatusDTO.State.ERROR);
        assertThat(state.message).isEqualTo("Test error message");
        assertThat(state.percent.get()).isEqualTo(50); // Should preserve progress
    }

    @Test
    void fail_withInvalidJobId_shouldNotThrowException() {
        assertThatCode(() -> jobs.fail("non-existent", "error"))
                .doesNotThrowAnyException();
    }

    @Test
    void complete_shouldSetDoneStateAndResults() {
        String jobId = jobs.createJob();
        List<AnalyticsResponseDTO> results = Arrays.asList(
                new AnalyticsResponseDTO(),
                new AnalyticsResponseDTO()
        );
        
        jobs.complete(jobId, results);
        
        AnalyticsProcessingJobs.JobState state = jobs.getJob(jobId);
        assertThat(state.getState()).isEqualTo(ProcessingStatusDTO.State.DONE);
        assertThat(state.getResults()).isEqualTo(results);
        assertThat(state.percent.get()).isEqualTo(100);
    }

    @Test
    void complete_withInvalidJobId_shouldNotThrowException() {
        assertThatCode(() -> jobs.complete("non-existent", Arrays.asList()))
                .doesNotThrowAnyException();
    }

    @Test
    void clear_shouldRemoveJob() {
        String jobId = jobs.createJob();
        
        jobs.clear(jobId);
        
        assertThat(jobs.getJob(jobId)).isNull();
    }

    @Test
    void toDto_withValidJobState_shouldConvertToDTO() {
        String jobId = jobs.createJob();
        jobs.update(jobId, 75, "file.csv");
        AnalyticsProcessingJobs.JobState state = jobs.getJob(jobId);
        
        ProcessingStatusDTO dto = jobs.toDto(state, false);
        
        assertThat(dto).isNotNull();
        assertThat(dto.getJobId()).isEqualTo(jobId);
        assertThat(dto.getState()).isEqualTo(ProcessingStatusDTO.State.RUNNING);
        assertThat(dto.getPercent()).isEqualTo(75);
        assertThat(dto.getCurrentFile()).isEqualTo("file.csv");
        assertThat(dto.getResults()).isNull(); // includeResults = false
    }

    @Test
    void toDto_withIncludeResults_shouldIncludeResults() {
        String jobId = jobs.createJob();
        List<AnalyticsResponseDTO> results = Arrays.asList(new AnalyticsResponseDTO());
        jobs.complete(jobId, results);
        AnalyticsProcessingJobs.JobState state = jobs.getJob(jobId);
        
        ProcessingStatusDTO dto = jobs.toDto(state, true);
        
        assertThat(dto.getResults()).isEqualTo(results);
    }

    @Test
    void toDto_withNullState_shouldReturnNull() {
        ProcessingStatusDTO dto = jobs.toDto(null, true);
        
        assertThat(dto).isNull();
    }

    @Test
    void concurrentAccess_shouldBeSafe() throws InterruptedException {
        String jobId = jobs.createJob();
        
        // Simulate concurrent updates from multiple threads
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    jobs.update(jobId, (threadId * 10 + j) % 101, "file-" + threadId);
                }
            });
            threads[i].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Job should still be accessible and in valid state
        AnalyticsProcessingJobs.JobState state = jobs.getJob(jobId);
        assertThat(state).isNotNull();
        assertThat(state.percent.get()).isBetween(0, 100);
    }
}
