package org.taniwha.service.jobs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.dto.ProcessingStatusDTO;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

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
        assertThat(state.getJobId()).isEqualTo(jobId);
        assertThat(state.getState()).isEqualTo(ProcessingStatusDTO.State.RUNNING);
        assertThat(state.getPercent().get()).isZero();
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
        assertThat(state.getPercent().get()).isEqualTo(50);
        assertThat(state.getCurrentFile()).isEqualTo("test-file.csv");
    }

    @Test
    void update_shouldClampPercentageTo0_100Range() {
        String jobId = jobs.createJob();

        jobs.update(jobId, 150, "file.csv");
        assertThat(jobs.getJob(jobId).getPercent().get()).isEqualTo(100);

        jobs.update(jobId, -50, "file.csv");
        assertThat(jobs.getJob(jobId).getPercent().get()).isZero();
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
        assertThat(state.getMessage()).isEqualTo("Test error message");
        assertThat(state.getPercent().get()).isEqualTo(50);
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
        assertThat(state.getPercent().get()).isEqualTo(100);
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
        assertThat(dto.getResults()).isNull();
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

        AnalyticsProcessingJobs.JobState state = jobs.getJob(jobId);
        assertThat(state).isNotNull();
        assertThat(state.getPercent().get()).isBetween(0, 100);
    }

    // -------------------------------------------------------------------------
    // cancel + isCanceled
    // -------------------------------------------------------------------------

    @Test
    void cancel_runningJob_withFuture_cancelsItAndSetsState() {
        String jobId = jobs.createJob();
        java.util.concurrent.Future<?> future = mock(java.util.concurrent.Future.class);
        jobs.attachFuture(jobId, future);

        jobs.cancel(jobId, "user canceled");

        assertThat(jobs.getJob(jobId).getState()).isEqualTo(ProcessingStatusDTO.State.CANCELED);
        assertThat(jobs.getJob(jobId).getMessage()).isEqualTo("user canceled");
        verify(future).cancel(true);
    }

    @Test
    void cancel_runningJob_noFuture_setsStateWithoutThrow() {
        String jobId = jobs.createJob();

        jobs.cancel(jobId, "no future");

        assertThat(jobs.getJob(jobId).getState()).isEqualTo(ProcessingStatusDTO.State.CANCELED);
        assertThat(jobs.getJob(jobId).getMessage()).isEqualTo("no future");
    }

    @Test
    void cancel_nullMessage_usesDefaultMessage() {
        String jobId = jobs.createJob();

        jobs.cancel(jobId, null);

        assertThat(jobs.getJob(jobId).getMessage()).isEqualTo("Job canceled");
    }

    @Test
    void cancel_alreadyDone_isNoOp() {
        String jobId = jobs.createJob();
        jobs.complete(jobId, List.of());

        jobs.cancel(jobId, "too late");

        assertThat(jobs.getJob(jobId).getState()).isEqualTo(ProcessingStatusDTO.State.DONE);
    }

    @Test
    void cancel_alreadyError_isNoOp() {
        String jobId = jobs.createJob();
        jobs.fail(jobId, "failed");

        jobs.cancel(jobId, "too late");

        assertThat(jobs.getJob(jobId).getState()).isEqualTo(ProcessingStatusDTO.State.ERROR);
    }

    @Test
    void cancel_alreadyCanceled_isNoOp() {
        String jobId = jobs.createJob();
        jobs.cancel(jobId, "first");
        jobs.cancel(jobId, "second");

        assertThat(jobs.getJob(jobId).getMessage()).isEqualTo("first");
    }

    @Test
    void cancel_unknownJobId_doesNotThrow() {
        assertThatCode(() -> jobs.cancel("ghost", "x")).doesNotThrowAnyException();
    }

    @Test
    void isCanceled_runningJob_returnsFalse() {
        String jobId = jobs.createJob();
        assertThat(jobs.isCanceled(jobId)).isFalse();
    }

    @Test
    void isCanceled_canceledJob_returnsTrue() {
        String jobId = jobs.createJob();
        jobs.cancel(jobId, "c");
        assertThat(jobs.isCanceled(jobId)).isTrue();
    }

    @Test
    void isCanceled_unknownJobId_returnsTrue() {
        assertThat(jobs.isCanceled("ghost")).isTrue();
    }

    // -------------------------------------------------------------------------
    // attachFuture
    // -------------------------------------------------------------------------

    @Test
    void attachFuture_knownJob_storesFuture() {
        String jobId = jobs.createJob();
        java.util.concurrent.Future<?> future = mock(java.util.concurrent.Future.class);

        jobs.attachFuture(jobId, future);

        assertThat(jobs.getJob(jobId).getFuture()).isSameAs(future);
    }

    @Test
    void attachFuture_unknownJob_doesNotThrow() {
        java.util.concurrent.Future<?> future = mock(java.util.concurrent.Future.class);
        assertThatCode(() -> jobs.attachFuture("ghost", future)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Guard branches in update / fail / complete when already CANCELED
    // -------------------------------------------------------------------------

    @Test
    void update_canceledJob_isNoOp() {
        String jobId = jobs.createJob();
        jobs.cancel(jobId, "c");

        jobs.update(jobId, 50, "file.csv");

        assertThat(jobs.getJob(jobId).getCurrentFile()).isNull();
    }

    @Test
    void fail_canceledJob_isNoOp() {
        String jobId = jobs.createJob();
        jobs.cancel(jobId, "c");

        jobs.fail(jobId, "error");

        assertThat(jobs.getJob(jobId).getState()).isEqualTo(ProcessingStatusDTO.State.CANCELED);
    }

    @Test
    void complete_canceledJob_isNoOp() {
        String jobId = jobs.createJob();
        jobs.cancel(jobId, "c");

        jobs.complete(jobId, List.of());

        assertThat(jobs.getJob(jobId).getState()).isEqualTo(ProcessingStatusDTO.State.CANCELED);
    }
}