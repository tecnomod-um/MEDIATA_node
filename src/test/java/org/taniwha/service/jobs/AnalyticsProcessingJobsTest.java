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

    }

    // -----------------------------------------------------------------------
    // cancel / attachFuture / isCanceled
    // -----------------------------------------------------------------------

    @Test
    void cancel_withRunningJob_setsCanceledState() {
        String jobId = jobs.createJob();

        jobs.cancel(jobId, "User canceled");

        AnalyticsProcessingJobs.JobState state = jobs.getJob(jobId);
        assertThat(state.getState()).isEqualTo(ProcessingStatusDTO.State.CANCELED);
        assertThat(state.getMessage()).isEqualTo("User canceled");
    }

    @Test
    void cancel_withNullMessage_usesDefaultMessage() {
        String jobId = jobs.createJob();

        jobs.cancel(jobId, null);

        assertThat(jobs.getJob(jobId).getMessage()).isEqualTo("Job canceled");
    }

    @Test
    void cancel_alreadyDoneJob_doesNothing() {
        String jobId = jobs.createJob();
        jobs.complete(jobId, List.of());

        jobs.cancel(jobId, "too late");

        assertThat(jobs.getJob(jobId).getState()).isEqualTo(ProcessingStatusDTO.State.DONE);
    }

    @Test
    void cancel_alreadyErrorJob_doesNothing() {
        String jobId = jobs.createJob();
        jobs.fail(jobId, "error");

        jobs.cancel(jobId, "late cancel");

        assertThat(jobs.getJob(jobId).getState()).isEqualTo(ProcessingStatusDTO.State.ERROR);
    }

    @Test
    void cancel_alreadyCanceledJob_doesNothing() {
        String jobId = jobs.createJob();
        jobs.cancel(jobId, "first");

        jobs.cancel(jobId, "second");

        assertThat(jobs.getJob(jobId).getMessage()).isEqualTo("first");
    }

    @Test
    void cancel_withUnknownJobId_doesNotThrow() {
        assertThatCode(() -> jobs.cancel("non-existent", "msg"))
                .doesNotThrowAnyException();
    }

    @Test
    void attachFuture_setsTheFuture() {
        String jobId = jobs.createJob();
        java.util.concurrent.Future<?> future = mock(java.util.concurrent.Future.class);

        jobs.attachFuture(jobId, future);

        assertThat(jobs.getJob(jobId).getFuture()).isSameAs(future);
    }

    @Test
    void attachFuture_withUnknownJobId_doesNotThrow() {
        java.util.concurrent.Future<?> future = mock(java.util.concurrent.Future.class);

        assertThatCode(() -> jobs.attachFuture("missing", future))
                .doesNotThrowAnyException();
    }

    @Test
    void isCanceled_forRunningJob_returnsFalse() {
        String jobId = jobs.createJob();

        assertThat(jobs.isCanceled(jobId)).isFalse();
    }

    @Test
    void isCanceled_forCanceledJob_returnsTrue() {
        String jobId = jobs.createJob();
        jobs.cancel(jobId, "done");

        assertThat(jobs.isCanceled(jobId)).isTrue();
    }

    @Test
    void isCanceled_forUnknownJobId_returnsTrue() {
        assertThat(jobs.isCanceled("non-existent")).isTrue();
    }

    @Test
    void cancel_withFuture_cancelsFuture() {
        String jobId = jobs.createJob();
        java.util.concurrent.Future<?> future = mock(java.util.concurrent.Future.class);
        jobs.attachFuture(jobId, future);

        jobs.cancel(jobId, "stop");

        verify(future, times(1)).cancel(true);
    }

    @Test
    void fail_onCanceledJob_doesNotOverwriteState() {
        String jobId = jobs.createJob();
        jobs.cancel(jobId, "canceled first");

        jobs.fail(jobId, "error after cancel");

        assertThat(jobs.getJob(jobId).getState()).isEqualTo(ProcessingStatusDTO.State.CANCELED);
    }

    @Test
    void complete_onCanceledJob_doesNotOverwriteState() {
        String jobId = jobs.createJob();
        jobs.cancel(jobId, "canceled first");

        jobs.complete(jobId, List.of());

        assertThat(jobs.getJob(jobId).getState()).isEqualTo(ProcessingStatusDTO.State.CANCELED);
    }

    @Test
    void update_onCanceledJob_doesNotUpdate() {
        String jobId = jobs.createJob();
        jobs.cancel(jobId, "canceled");

        jobs.update(jobId, 99, "file.csv");

        assertThat(jobs.getJob(jobId).getCurrentFile()).isNull();
    }
}