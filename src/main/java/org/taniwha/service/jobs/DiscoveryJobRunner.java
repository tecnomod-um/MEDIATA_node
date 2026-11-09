package org.taniwha.service.jobs;

import org.taniwha.dto.AnalyticsResponseDTO;
import java.util.List;
import java.util.concurrent.*;

/** Shared completion and cancellation handling for profiling and refresh jobs. */
public final class DiscoveryJobRunner {
    private DiscoveryJobRunner() {}

    public static void submit(String id, ExecutorService executor, AnalyticsProcessingJobs jobs,
                              Callable<List<AnalyticsResponseDTO>> operation) {
        try {
            Future<?> future = executor.submit(() -> {
                try {
                    List<AnalyticsResponseDTO> results = operation.call();
                    if (!jobs.isCanceled(id)) jobs.complete(id, results);
                } catch (InterruptedException | CancellationException canceled) {
                    Thread.currentThread().interrupt();
                    jobs.cancel(id, "Discovery processing canceled");
                } catch (Exception failed) {
                    if (!jobs.isCanceled(id)) jobs.fail(id, "Error processing statistics.");
                }
            });
            jobs.attachFuture(id, future);
        } catch (RejectedExecutionException busy) {
            jobs.clear(id);
            throw busy;
        }
    }
}
