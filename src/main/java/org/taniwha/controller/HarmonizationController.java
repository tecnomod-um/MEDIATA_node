package org.taniwha.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.DataCleaningOptionsDTO;
import org.taniwha.dto.FileMappingsDTO;
import org.taniwha.dto.HarmonizationStartDTO;
import org.taniwha.dto.HarmonizationStatusDTO;
import org.taniwha.dto.mapping.MappingSpecDTO;
import org.taniwha.service.HarmonizerService;
import org.taniwha.service.jobs.HarmonizationProcessingJobs;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/harmonization")
@Tag(name = "Harmonization", description = "Apply mapping specifications and monitor harmonization jobs.")
public class HarmonizationController {

    private static final Logger logger = LoggerFactory.getLogger(HarmonizationController.class);

    private final HarmonizerService harmonizerService;
    private final HarmonizationProcessingJobs jobs;

    public HarmonizationController(HarmonizerService harmonizerService,
                                   HarmonizationProcessingJobs jobs) {
        this.harmonizerService = harmonizerService;
        this.jobs = jobs;
    }

    @Operation(summary = "Start harmonization from a mapping specification")
    @PostMapping("/parse")
    public ResponseEntity<HarmonizationStartDTO> parseAndClean(@RequestBody FileMappingsDTO dto) {
        try {
            DataCleaningOptionsDTO cleanOpts = dto.getCleaningOptions();
            Map<String, List<String>> fileMappings = dto.getFileMappings();
            MappingSpecDTO mappingSpec = dto.getMappingSpec();

            if (fileMappings == null || fileMappings.isEmpty() || mappingSpec == null) {
                return ResponseEntity.badRequest().build();
            }

            String jobId = jobs.createJob();
            logger.info("Starting harmonization parse jobId={} for {} config file(s)", jobId, fileMappings.size());

            harmonizerService.startParseJob(jobId, mappingSpec, fileMappings, cleanOpts);

            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new HarmonizationStartDTO(jobId, true));
        } catch (Exception e) {
            logger.error("Error starting file parsing job", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @Operation(summary = "Get harmonization job status")
    @GetMapping("/parse/status/{jobId}")
    public ResponseEntity<HarmonizationStatusDTO> parseStatus(@PathVariable String jobId) {
        HarmonizationProcessingJobs.JobState s = jobs.getJob(jobId);
        if (s == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(jobs.toDto(s, false));
    }

    @Operation(summary = "Get harmonization job results")
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
