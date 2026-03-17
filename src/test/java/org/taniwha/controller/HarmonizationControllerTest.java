package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.dto.DataCleaningOptionsDTO;
import org.taniwha.dto.FileMappingsDTO;
import org.taniwha.dto.HarmonizationStatusDTO;
import org.taniwha.service.HarmonizerService;
import org.taniwha.service.jobs.HarmonizationProcessingJobs;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HarmonizationControllerTest {

    private MockMvc mvc;
    private HarmonizerService harmonizerService;
    private HarmonizationProcessingJobs jobs;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        harmonizerService = mock(HarmonizerService.class);
        jobs = new HarmonizationProcessingJobs();
        objectMapper = new ObjectMapper();

        mvc = MockMvcBuilders
                .standaloneSetup(new HarmonizationController(harmonizerService, jobs, objectMapper))
                .build();
    }

    @Test
    void parseAndClean_success_returns202AndJobId() throws Exception {
        Map<String, List<String>> mapping = Map.of("f1.csv", List.of("colA", "colB"));
        String mappingsJson = objectMapper.writeValueAsString(mapping);
        String configs = "{\"foo\":\"bar\"}";

        FileMappingsDTO dto = new FileMappingsDTO();
        dto.setFileMappings(mappingsJson);
        dto.setConfigs(configs);
        dto.setCleaningOptions(new DataCleaningOptionsDTO());

        mvc.perform(post("/api/harmonization/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isString())
                .andExpect(jsonPath("$.progress").value(true));

        verify(harmonizerService, times(1))
                .startParseJob(anyString(), eq(configs), eq(mapping), any(DataCleaningOptionsDTO.class));
    }

    @Test
    void parseAndClean_badJsonInMappings_returns500() throws Exception {
        FileMappingsDTO dto = new FileMappingsDTO();
        dto.setFileMappings("not a valid JSON");
        dto.setConfigs("");
        dto.setCleaningOptions(new DataCleaningOptionsDTO());

        mvc.perform(post("/api/harmonization/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isInternalServerError());

        verifyNoInteractions(harmonizerService);
    }

    @Test
    void parseStatus_withUnknownJob_returns404() throws Exception {
        mvc.perform(get("/api/harmonization/parse/status/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void parseStatus_withRunningJob_returnsStatus() throws Exception {
        String jobId = jobs.createJob();
        jobs.update(jobId, 35, "dataset.csv", "Processing dataset.csv");

        mvc.perform(get("/api/harmonization/parse/status/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.state").value(HarmonizationStatusDTO.State.RUNNING.name()))
                .andExpect(jsonPath("$.percent").value(35))
                .andExpect(jsonPath("$.currentDataset").value("dataset.csv"))
                .andExpect(jsonPath("$.message").value("Processing dataset.csv"));
    }

    @Test
    void parseResult_withDoneJob_returns200AndBody() throws Exception {
        String jobId = jobs.createJob();
        jobs.complete(jobId, "CLEANED_OK");

        mvc.perform(get("/api/harmonization/parse/result/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(content().string("CLEANED_OK"));
    }

    @Test
    void parseResult_withErrorJob_returns500AndBody() throws Exception {
        String jobId = jobs.createJob();
        jobs.fail(jobId, "oops!");

        mvc.perform(get("/api/harmonization/parse/result/{jobId}", jobId))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("oops!"));
    }

    @Test
    void parseResult_withRunningJob_returns409() throws Exception {
        String jobId = jobs.createJob();

        mvc.perform(get("/api/harmonization/parse/result/{jobId}", jobId))
                .andExpect(status().isConflict());
    }
}