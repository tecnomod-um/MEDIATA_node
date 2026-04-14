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
import org.taniwha.dto.mapping.MappingDefinitionDTO;
import org.taniwha.dto.mapping.MappingInputDTO;
import org.taniwha.dto.mapping.MappingMatcherDTO;
import org.taniwha.dto.mapping.MappingMetadataDTO;
import org.taniwha.dto.mapping.MappingRuleDTO;
import org.taniwha.dto.mapping.MappingSpecDTO;
import org.taniwha.dto.mapping.RuleResultDTO;
import org.taniwha.service.HarmonizerService;
import org.taniwha.service.jobs.HarmonizationProcessingJobs;

import java.util.List;
import java.util.Locale;
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
                .standaloneSetup(new HarmonizationController(harmonizerService, jobs))
                .build();
    }

    @Test
    void parseAndClean_success_returns202AndJobId() throws Exception {
        Map<String, List<String>> fileMappings = Map.of("cfg1", List.of("d1.csv"));

        FileMappingsDTO dto = new FileMappingsDTO();
        dto.setFileMappings(fileMappings);
        dto.setMappingSpec(minimalStandardSpec());
        dto.setCleaningOptions(new DataCleaningOptionsDTO());

        mvc.perform(post("/api/harmonization/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isString())
                .andExpect(jsonPath("$.progress").value(true));

        verify(harmonizerService, times(1))
                .startParseJob(anyString(), any(MappingSpecDTO.class), eq(fileMappings), any(DataCleaningOptionsDTO.class));
    }

    @Test
    void parseAndClean_badBody_returns400() throws Exception {
        mvc.perform(post("/api/harmonization/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileMappings\":\"not-an-object\"}"))
                .andExpect(status().isBadRequest());

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

    private MappingSpecDTO minimalStandardSpec() {
        MappingMatcherDTO matcher = new MappingMatcherDTO();
        matcher.setSourceId("n1::elements.csv");
        matcher.setColumn("a");
        matcher.setMatchType("exact");
        matcher.setValue("foo");

        MappingRuleDTO rule = new MappingRuleDTO();
        rule.setId("r1");
        rule.setLogic(toJsonLogic(matcher));
        rule.setThen(literalResult("YES"));
        rule.setMetadata(new MappingMetadataDTO());

        MappingInputDTO input = new MappingInputDTO();
        input.setSourceId("n1::elements.csv");
        input.setColumn("a");

        MappingDefinitionDTO mapping = new MappingDefinitionDTO();
        mapping.setId("m1");
        mapping.setTargetField("mapped");
        mapping.setMappingType("standard");
        mapping.setInputs(List.of(input));
        mapping.setRules(List.of(rule));
        mapping.setMetadata(new MappingMetadataDTO());

        MappingSpecDTO spec = new MappingSpecDTO();
        spec.setSpecVersion("1.0.0");
        spec.setMappings(List.of(mapping));
        return spec;
    }

    private RuleResultDTO literalResult(String value) {
        RuleResultDTO result = new RuleResultDTO();
        result.setKind("literal");
        result.setValue(value);
        return result;
    }

    private Map<String, Object> toJsonLogic(MappingMatcherDTO matcher) {
        String sourceId = matcher.getSourceId();
        String column = matcher.getColumn();
        String varPath = sourceId + "::" + column;
        String matchType = matcher.getMatchType() == null
                ? ""
                : matcher.getMatchType().trim().toLowerCase(Locale.ROOT);

        return switch (matchType) {
            case "exact" -> Map.of(
                    "==", List.of(
                            Map.of("var", varPath),
                            matcher.getValue()
                    )
            );
            case "type" -> Map.of(
                    "type", List.of(
                            Map.of("var", varPath),
                            matcher.getValueType()
                    )
            );
            case "range" -> Map.of(
                    "and", List.of(
                            Map.of(">=", List.of(Map.of("var", varPath), matcher.getMinValue())),
                            Map.of("<=", List.of(Map.of("var", varPath), matcher.getMaxValue()))
                    )
            );
            default -> throw new IllegalArgumentException("Unsupported matcher type in test: " + matcher.getMatchType());
        };
    }
}