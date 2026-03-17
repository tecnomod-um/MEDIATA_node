package org.taniwha.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.DataCleaningOptionsDTO;
import org.taniwha.dto.FileMappingsDTO;
import org.taniwha.dto.HarmonizationStartDTO;
import org.taniwha.dto.HarmonizationStatusDTO;
import org.taniwha.service.HarmonizerService;
import org.taniwha.service.jobs.HarmonizationProcessingJobs;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/harmonization")
public class HarmonizationController {

    private static final Logger logger = LoggerFactory.getLogger(HarmonizationController.class);

    private final HarmonizerService harmonizerService;
    private final HarmonizationProcessingJobs jobs;
    private final ObjectMapper objectMapper;

    public HarmonizationController(HarmonizerService harmonizerService,
                                   HarmonizationProcessingJobs jobs,
                                   ObjectMapper objectMapper) {
        this.harmonizerService = harmonizerService;
        this.jobs = jobs;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/parse")
    public ResponseEntity<HarmonizationStartDTO> parseAndClean(@RequestBody FileMappingsDTO dto) {
        try {
            Map<String, List<String>> fileMappings = objectMapper.readValue(
                    dto.getFileMappings(),
                    new TypeReference<>() {}
            );

            String configs = dto.getConfigs();
            DataCleaningOptionsDTO cleanOpts = dto.getCleaningOptions();

            String jobId = jobs.createJob();
            logger.info("Starting harmonization parse jobId={} for {} config file(s)", jobId, fileMappings.size());

            harmonizerService.startParseJob(jobId, configs, fileMappings, cleanOpts);

            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new HarmonizationStartDTO(jobId, true));
        } catch (Exception e) {
            logger.error("Error starting file parsing job", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/parse/status/{jobId}")
    public ResponseEntity<HarmonizationStatusDTO> parseStatus(@PathVariable String jobId) {
        HarmonizationProcessingJobs.JobState s = jobs.getJob(jobId);
        if (s == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(jobs.toDto(s, false));
    }

    @GetMapping("/parse/result/{jobId}")
    public ResponseEntity<String> parseResult(@PathVariable String jobId) {
        HarmonizationProcessingJobs.JobState s = jobs.getJob(jobId);
        if (s == null) return ResponseEntity.notFound().build();

        if (s.getState() == HarmonizationStatusDTO.State.ERROR) {
            String msg = s.getMessage() != null ? s.getMessage() : "Error processing files.";
            jobs.clear(jobId);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(msg);
        }

        if (s.getState() != HarmonizationStatusDTO.State.DONE) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        String out = s.getResult() != null ? s.getResult() : "Files processed successfully.";
        jobs.clear(jobId);
        return ResponseEntity.ok(out);
    }
}