package org.taniwha.service.jobs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CleaningProcessingJobsTest {

    private CleaningProcessingJobs jobs;

    @BeforeEach
    void setUp() {
        jobs = new CleaningProcessingJobs();
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

        CleaningProcessingJobs.JobState state = jobs.getJob(jobId);

        assertThat(state).isNotNull();
        assertThat(state.getJobId()).isEqualTo(jobId);
        assertThat(state.getState()).isEqualTo(CleaningProcessingJobs.State.RUNNING);
        assertThat(state.getPercent().get()).isZero();
        assertThat(state.getCurrentFile()).isNull();
        assertThat(state.getMessage()).isNull();
        assertThat(state.getResult()).isNull();
    }

    @Test
    void getJob_withInvalidJobId_shouldReturnNull() {
        assertThat(jobs.getJob("missing")).isNull();
    }

    @Test
    void update_shouldUpdateProgressCurrentFileAndMessage() {
        String jobId = jobs.createJob();

        jobs.update(jobId, 45, "file.csv", "Cleaning file.csv");

        CleaningProcessingJobs.JobState state = jobs.getJob(jobId);
        assertThat(state.getPercent().get()).isEqualTo(45);
        assertThat(state.getCurrentFile()).isEqualTo("file.csv");
        assertThat(state.getMessage()).isEqualTo("Cleaning file.csv");
        assertThat(state.getState()).isEqualTo(CleaningProcessingJobs.State.RUNNING);
    }

    @Test
    void update_shouldClampPercentageToRange() {
        String jobId = jobs.createJob();

        jobs.update(jobId, 150, "file.csv", "too high");
        assertThat(jobs.getJob(jobId).getPercent().get()).isEqualTo(100);

        jobs.update(jobId, -10, "file.csv", "too low");
        assertThat(jobs.getJob(jobId).getPercent().get()).isZero();
    }

    @Test
    void update_withInvalidJobId_shouldNotThrow() {
        assertThatCode(() -> jobs.update("missing", 50, "file.csv", "msg"))
                .doesNotThrowAnyException();
    }

    @Test
    void complete_shouldSetDoneStateAndResult() {
        String jobId = jobs.createJob();
        jobs.update(jobId, 70, "file.csv", "Almost done");

        jobs.complete(jobId, "Cleaning completed successfully.");

        CleaningProcessingJobs.JobState state = jobs.getJob(jobId);
        assertThat(state.getState()).isEqualTo(CleaningProcessingJobs.State.DONE);
        assertThat(state.getPercent().get()).isEqualTo(100);
        assertThat(state.getCurrentFile()).isNull();
        assertThat(state.getMessage()).isEqualTo("Cleaning completed successfully.");
        assertThat(state.getResult()).isEqualTo("Cleaning completed successfully.");
    }

    @Test
    void complete_withInvalidJobId_shouldNotThrow() {
        assertThatCode(() -> jobs.complete("missing", "done"))
                .doesNotThrowAnyException();
    }

    @Test
    void fail_shouldSetErrorStateAndMessage() {
        String jobId = jobs.createJob();
        jobs.update(jobId, 55, "file.csv", "working");

        jobs.fail(jobId, "Boom");

        CleaningProcessingJobs.JobState state = jobs.getJob(jobId);
        assertThat(state.getState()).isEqualTo(CleaningProcessingJobs.State.ERROR);
        assertThat(state.getMessage()).isEqualTo("Boom");
        assertThat(state.getPercent().get()).isEqualTo(55);
    }

    @Test
    void fail_withInvalidJobId_shouldNotThrow() {
        assertThatCode(() -> jobs.fail("missing", "error"))
                .doesNotThrowAnyException();
    }

    @Test
    void clear_shouldRemoveJob() {
        String jobId = jobs.createJob();

        jobs.clear(jobId);

        assertThat(jobs.getJob(jobId)).isNull();
    }
}