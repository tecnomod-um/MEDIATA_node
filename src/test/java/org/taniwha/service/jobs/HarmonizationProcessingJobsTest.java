package org.taniwha.service.jobs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.taniwha.dto.HarmonizationStatusDTO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class HarmonizationProcessingJobsTest {

    private HarmonizationProcessingJobs jobs;

    @BeforeEach
    void setUp() {
        jobs = new HarmonizationProcessingJobs();
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

        HarmonizationProcessingJobs.JobState state = jobs.getJob(jobId);

        assertThat(state).isNotNull();
        assertThat(state.getJobId()).isEqualTo(jobId);
        assertThat(state.getState()).isEqualTo(HarmonizationStatusDTO.State.RUNNING);
        assertThat(state.getPercent().get()).isZero();
        assertThat(state.getCurrentDataset()).isNull();
        assertThat(state.getMessage()).isNull();
        assertThat(state.getResult()).isNull();
    }

    @Test
    void getJob_withInvalidJobId_shouldReturnNull() {
        assertThat(jobs.getJob("missing")).isNull();
    }

    @Test
    void update_shouldUpdateProgressDatasetAndMessage() {
        String jobId = jobs.createJob();

        jobs.update(jobId, 35, "dataset.csv", "Processing dataset.csv");

        HarmonizationProcessingJobs.JobState state = jobs.getJob(jobId);
        assertThat(state.getPercent().get()).isEqualTo(35);
        assertThat(state.getCurrentDataset()).isEqualTo("dataset.csv");
        assertThat(state.getMessage()).isEqualTo("Processing dataset.csv");
        assertThat(state.getState()).isEqualTo(HarmonizationStatusDTO.State.RUNNING);
    }

    @Test
    void update_shouldClampPercentageToRange() {
        String jobId = jobs.createJob();

        jobs.update(jobId, 120, "dataset.csv", "high");
        assertThat(jobs.getJob(jobId).getPercent().get()).isEqualTo(100);

        jobs.update(jobId, -25, "dataset.csv", "low");
        assertThat(jobs.getJob(jobId).getPercent().get()).isZero();
    }

    @Test
    void update_withInvalidJobId_shouldNotThrow() {
        assertThatCode(() -> jobs.update("missing", 10, "dataset.csv", "msg"))
                .doesNotThrowAnyException();
    }

    @Test
    void complete_shouldSetDoneStateAndResult() {
        String jobId = jobs.createJob();
        jobs.update(jobId, 80, "dataset.csv", "Almost done");

        jobs.complete(jobId, "Files processed successfully.");

        HarmonizationProcessingJobs.JobState state = jobs.getJob(jobId);
        assertThat(state.getState()).isEqualTo(HarmonizationStatusDTO.State.DONE);
        assertThat(state.getPercent().get()).isEqualTo(100);
        assertThat(state.getCurrentDataset()).isNull();
        assertThat(state.getMessage()).isEqualTo("Files processed successfully.");
        assertThat(state.getResult()).isEqualTo("Files processed successfully.");
    }

    @Test
    void complete_withInvalidJobId_shouldNotThrow() {
        assertThatCode(() -> jobs.complete("missing", "done"))
                .doesNotThrowAnyException();
    }

    @Test
    void fail_shouldSetErrorStateAndMessage() {
        String jobId = jobs.createJob();
        jobs.update(jobId, 65, "dataset.csv", "working");

        jobs.fail(jobId, "Failure");

        HarmonizationProcessingJobs.JobState state = jobs.getJob(jobId);
        assertThat(state.getState()).isEqualTo(HarmonizationStatusDTO.State.ERROR);
        assertThat(state.getMessage()).isEqualTo("Failure");
        assertThat(state.getPercent().get()).isEqualTo(65);
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

    @Test
    void toDto_withValidState_shouldMapFieldsWithoutResult() {
        String jobId = jobs.createJob();
        jobs.update(jobId, 77, "dataset.csv", "Working");

        HarmonizationStatusDTO dto = jobs.toDto(jobs.getJob(jobId), false);

        assertThat(dto).isNotNull();
        assertThat(dto.getJobId()).isEqualTo(jobId);
        assertThat(dto.getState()).isEqualTo(HarmonizationStatusDTO.State.RUNNING);
        assertThat(dto.getPercent()).isEqualTo(77);
        assertThat(dto.getCurrentDataset()).isEqualTo("dataset.csv");
        assertThat(dto.getMessage()).isEqualTo("Working");
        assertThat(dto.getResult()).isNull();
    }

    @Test
    void toDto_withIncludeResult_shouldIncludeResult() {
        String jobId = jobs.createJob();
        jobs.complete(jobId, "done");

        HarmonizationStatusDTO dto = jobs.toDto(jobs.getJob(jobId), true);

        assertThat(dto.getResult()).isEqualTo("done");
    }

    @Test
    void toDto_withNullState_shouldReturnNull() {
        assertThat(jobs.toDto(null, true)).isNull();
    }
}