package org.taniwha.service.jobs;

import org.junit.jupiter.api.Test;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.dto.ProcessingStatusDTO;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class DiscoveryJobRunnerTest {
    @Test
    void completesOnlyWithFinishedResults() throws Exception {
        AnalyticsProcessingJobs jobs = new AnalyticsProcessingJobs();
        String id = jobs.createJob();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DiscoveryJobRunner.submit(id, executor, jobs, () -> List.of(new AnalyticsResponseDTO("released")));
            jobs.getJob(id).getFuture().get(5, TimeUnit.SECONDS);
            assertThat(jobs.getJob(id).getState()).isEqualTo(ProcessingStatusDTO.State.DONE);
            assertThat(jobs.getJob(id).getResults()).hasSize(1);
            assertThat(jobs.getJob(id).getPercent().get()).isEqualTo(100);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void masksFailuresAndClearsRejectedJobs() throws Exception {
        AnalyticsProcessingJobs jobs = new AnalyticsProcessingJobs();
        String id = jobs.createJob();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DiscoveryJobRunner.submit(id, executor, jobs, () -> { throw new IllegalStateException("private detail"); });
            jobs.getJob(id).getFuture().get(5, TimeUnit.SECONDS);
            assertThat(jobs.getJob(id).getState()).isEqualTo(ProcessingStatusDTO.State.ERROR);
            assertThat(jobs.getJob(id).getMessage()).isEqualTo("Error processing statistics.");
        } finally { executor.shutdownNow(); }
        String rejected = jobs.createJob();
        assertThatThrownBy(() -> DiscoveryJobRunner.submit(rejected, executor, jobs, List::of))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(jobs.getJob(rejected)).isNull();
    }

    @Test
    void cancellationBeforeAttachingFutureInterruptsWorkAndCannotBecomeDone() throws Exception {
        AnalyticsProcessingJobs jobs = new AnalyticsProcessingJobs();
        String id = jobs.createJob();
        jobs.cancel(id, "left page");
        FutureTask<Void> future = new FutureTask<>(() -> null);
        jobs.attachFuture(id, future);
        assertThat(future.isCancelled()).isTrue();
        jobs.complete(id, List.of(new AnalyticsResponseDTO("late")));
        assertThat(jobs.getJob(id).getState()).isEqualTo(ProcessingStatusDTO.State.CANCELED);
        assertThat(jobs.getJob(id).getResults()).isNull();
    }

    @Test
    void interruptionMarksJobCanceled() throws Exception {
        AnalyticsProcessingJobs jobs = new AnalyticsProcessingJobs();
        String id = jobs.createJob();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch finished = new CountDownLatch(1);
        try {
            DiscoveryJobRunner.submit(id, executor, jobs, () -> { throw new InterruptedException(); });
            executor.execute(finished::countDown);
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jobs.getJob(id).getState()).isEqualTo(ProcessingStatusDTO.State.CANCELED);
        } finally { executor.shutdownNow(); }
    }
}
