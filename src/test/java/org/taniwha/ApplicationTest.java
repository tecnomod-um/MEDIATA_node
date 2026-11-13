package org.taniwha;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.taniwha.service.NodeSyncService;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApplicationTest {

    @Test
    void stop_shutsSchedulerDownGracefully() throws Exception {
        Application application = new Application();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ReflectionTestUtils.setField(application, "scheduler", scheduler);
        ReflectionTestUtils.setField(application, "heartbeatInterval", 25L);

        when(scheduler.isShutdown()).thenReturn(false);
        when(scheduler.awaitTermination(25L, TimeUnit.MILLISECONDS)).thenReturn(true);

        application.stop();

        verify(scheduler).shutdown();
        verify(scheduler, never()).shutdownNow();
        assertThat(ReflectionTestUtils.getField(application, "running")).isEqualTo(false);
    }

    @Test
    void stop_forcesShutdownWhenAwaitTerminationTimesOut() throws Exception {
        Application application = new Application();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ReflectionTestUtils.setField(application, "scheduler", scheduler);
        ReflectionTestUtils.setField(application, "heartbeatInterval", 25L);

        when(scheduler.isShutdown()).thenReturn(false);
        when(scheduler.awaitTermination(25L, TimeUnit.MILLISECONDS)).thenReturn(false);

        application.stop();

        verify(scheduler).shutdown();
        verify(scheduler).shutdownNow();
    }

    @Test
    void stop_forcesShutdownWhenInterrupted() throws Exception {
        Application application = new Application();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ReflectionTestUtils.setField(application, "scheduler", scheduler);
        ReflectionTestUtils.setField(application, "heartbeatInterval", 25L);

        when(scheduler.isShutdown()).thenReturn(false);
        when(scheduler.awaitTermination(25L, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException("interrupted"));

        application.stop();

        verify(scheduler).shutdown();
        verify(scheduler).shutdownNow();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }

    @Test
    void createThreadFactory_namesHeartbeatThread() {
        Application application = new Application();

        ThreadFactory factory = ReflectionTestUtils.invokeMethod(application, "createThreadFactory");
        Thread thread = factory.newThread(() -> {});

        assertThat(thread.getName()).isEqualTo("Heartbeat");
    }

    @Test
    void startHeartbeatScheduler_sendsHeartbeatsUntilStopped() throws Exception {
        Application application = new Application();
        NodeSyncService nodeSyncService = mock(NodeSyncService.class);
        ReflectionTestUtils.setField(application, "heartbeatInterval", 10L);
        var sent = new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation -> { sent.countDown(); return null; }).when(nodeSyncService).sendHeartbeat();
        try {
            ReflectionTestUtils.invokeMethod(application, "startHeartbeatScheduler", nodeSyncService);
            assertThat(sent.await(10, TimeUnit.SECONDS)).isTrue();
            application.stop();
            var scheduler = (ScheduledExecutorService) ReflectionTestUtils.getField(application, "scheduler");
            assertThat(scheduler.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            clearInvocations(nodeSyncService);
            verifyNoInteractions(nodeSyncService);
        } finally { application.stop(); }
    }
}
