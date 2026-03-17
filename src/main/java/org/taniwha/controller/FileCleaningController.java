package org.taniwha.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.DataCleaningOptionsDTO;
import org.taniwha.model.FileCategory;
import org.taniwha.service.DataCleaningService;
import org.taniwha.service.jobs.CleaningProcessingJobs;

import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileCleaningController {

    private static final Logger logger = LoggerFactory.getLogger(FileCleaningController.class);

    private final DataCleaningService dataCleaningService;
    private final CleaningProcessingJobs cleaningJobs;

    public FileCleaningController(DataCleaningService dataCleaningService,
                                  CleaningProcessingJobs cleaningJobs) {
        this.dataCleaningService = dataCleaningService;
        this.cleaningJobs = cleaningJobs;
    }

    @PostMapping("/clean")
    public ResponseEntity<Void> cleanFile(
            @RequestParam FileCategory category,
            @RequestParam String name,
            @RequestBody(required = false) DataCleaningOptionsDTO options
    ) {
        logger.debug("Clean file category={} name={} options={}", category, name, options);
        dataCleaningService.cleanInPlace(category, name, options);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/clean/start")
    public ResponseEntity<Map<String, Object>> startCleanFile(
            @RequestParam FileCategory category,
            @RequestParam String name,
            @RequestBody(required = false) DataCleaningOptionsDTO options
    ) {
        logger.debug("Start clean file category={} name={} options={}", category, name, options);

        String jobId = cleaningJobs.createJob();
        dataCleaningService.startCleanJob(jobId, category, name, options);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "jobId", jobId,
                "accepted", true
        ));
    }

    @GetMapping("/clean/status/{jobId}")
    public ResponseEntity<Map<String, Object>> getCleanStatus(@PathVariable String jobId) {
        CleaningProcessingJobs.JobState s = cleaningJobs.getJob(jobId);
        if (s == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "jobId", s.getJobId(),
                "state", s.getState().name(),
                "percent", s.getPercent().get(),
                "currentFile", s.getCurrentFile() == null ? "" : s.getCurrentFile(),
                "message", s.getMessage() == null ? "" : s.getMessage()
        ));
    }

    @GetMapping("/clean/result/{jobId}")
    public ResponseEntity<Map<String, Object>> getCleanResult(@PathVariable String jobId) {
        CleaningProcessingJobs.JobState s = cleaningJobs.getJob(jobId);
        if (s == null) {
            return ResponseEntity.notFound().build();
        }

        if (s.getState() == CleaningProcessingJobs.State.ERROR) {
            String msg = s.getMessage() == null ? "Cleaning failed." : s.getMessage();
            cleaningJobs.clear(jobId);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "message", msg
            ));
        }

        if (s.getState() != CleaningProcessingJobs.State.DONE) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "Cleaning job is not finished yet."
            ));
        }

        String result = s.getResult() == null ? "Cleaning completed successfully." : s.getResult();
        cleaningJobs.clear(jobId);

        return ResponseEntity.ok(Map.of(
                "message", result
        ));
    }
}