package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.dto.DataCleaningOptionsDTO;
import org.taniwha.dto.FileMappingsDTO;
import org.taniwha.service.HarmonizerService;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HarmonizationControllerTest {

    private MockMvc mvc;
    private HarmonizerService harmonizerService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        harmonizerService = mock(HarmonizerService.class);
        objectMapper = new ObjectMapper();
        mvc = MockMvcBuilders
                .standaloneSetup(new HarmonizationController(harmonizerService, objectMapper))
                .build();
    }

    @Test
    void parseAndClean_success_returns200AndBody() throws Exception {
        Map<String, List<String>> mapping = Map.of("f1.csv", List.of("colA", "colB"));
        String mappingsJson = objectMapper.writeValueAsString(mapping);
        String configs = "{\"foo\":\"bar\"}";
        FileMappingsDTO dto = new FileMappingsDTO();
        dto.setFileMappings(mappingsJson);
        dto.setConfigs(configs);
        dto.setCleaningOptions(new DataCleaningOptionsDTO());

        when(harmonizerService.parseFiles(
                eq(configs),
                eq(mapping),
                any(DataCleaningOptionsDTO.class)))
                .thenReturn("CLEANED_OK");
        mvc.perform(post("/api/harmonization/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(content().string("CLEANED_OK"));
    }

    @Test
    void parseAndClean_serviceThrows_returns500AndErrorMessage() throws Exception {
        FileMappingsDTO dto = new FileMappingsDTO();
        dto.setFileMappings(objectMapper.writeValueAsString(Map.of()));
        dto.setConfigs("");
        dto.setCleaningOptions(new DataCleaningOptionsDTO());
        when(harmonizerService.parseFiles(anyString(), anyMap(), any())).thenThrow(new RuntimeException("oops!"));

        mvc.perform(post("/api/harmonization/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("Error processing files: oops!"));
    }

    @Test
    void parseAndClean_badJsonInMappings_returns500AndErrorMessage() throws Exception {
        FileMappingsDTO dto = new FileMappingsDTO();
        dto.setFileMappings("not a valid JSON");
        dto.setConfigs("");
        dto.setCleaningOptions(new DataCleaningOptionsDTO());

        mvc.perform(post("/api/harmonization/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(startsWith("Error processing files:")));

        verifyNoInteractions(harmonizerService);
    }
}
