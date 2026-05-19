package org.taniwha;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.taniwha.service.NodeSyncService;
import org.taniwha.view.Gui;

import java.awt.*;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class Application {
    private static final Logger logger = LoggerFactory.getLogger(Application.class);
    private static ConfigurableApplicationContext context;
    private ScheduledExecutorService scheduler;
    private boolean running = true;

    @Value("${heartbeat.interval.ms:60000}")
    private long heartbeatInterval;

    public static void main(String[] args) {
        boolean forceCli = Arrays.stream(args).anyMatch(a -> a.equalsIgnoreCase("--cli") || a.equalsIgnoreCase("--nogui")) || "cli".equalsIgnoreCase(System.getenv("TANIWHA_MODE"));
        boolean canShowGui = !GraphicsEnvironment.isHeadless();

        if (canShowGui && !forceCli) {
            try {
                Gui.main(args);
                return;
            } catch (Exception guiFailure) {
                logger.error("GUI startup failed; falling back to CLI mode.", guiFailure);
                // Continue to Spring Boot below
            }
        }
        launchSpringBootApp(args);
    }

    // Main starting point of the app
    public static void launchSpringBootApp(String[] args) {
        try {
            logger.debug("Initializing Spring Boot application...");
            context = SpringApplication.run(Application.class, args);

            Application app = context.getBean(Application.class);
            NodeSyncService nodeService = context.getBean(NodeSyncService.class);

            try {
                // Keep the node available locally even if the central backend is offline.
                nodeService.registerWithCentralBackend();
            } catch (Exception registrationFailure) {
                logger.warn("Node registration during startup failed; continuing without a central backend session.", registrationFailure);
            }
            app.startHeartbeatScheduler(nodeService);


        } catch (Exception e) {
            logger.error("Error launching Spring Boot application", e);
            if (context != null) context.close();
        }
    }

    public static void stopSpringBootApp() {
        if (context != null) {
            logger.info("Stopping Spring Boot application...");
            Application app = context.getBean(Application.class);
            app.stop();
            logger.info("Closing Spring Boot context...");
            context.close();
            logger.info("Spring Boot context closed.");
            System.exit(0);
        }
    }

    private void startHeartbeatScheduler(NodeSyncService nodeService) {
        scheduler = Executors.newSingleThreadScheduledExecutor(createThreadFactory());
        scheduler.scheduleAtFixedRate(() -> {
            if (running) {
                try {
                    logger.info("Sending heartbeat...");
                    nodeService.sendHeartbeat();
                } catch (Exception e) {
                    logger.error("Error in heartbeat task", e);
                }
            }
        }, 0, heartbeatInterval, TimeUnit.MILLISECONDS);
    }

    private ThreadFactory createThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("Heartbeat");
            return thread;
        };
    }

    public void stop() {
        logger.info("Stopping application...");
        running = false;
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(heartbeatInterval, TimeUnit.MILLISECONDS))
                    scheduler.shutdownNow();
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("Application heartbeat scheduler stopped.");
    }
}
