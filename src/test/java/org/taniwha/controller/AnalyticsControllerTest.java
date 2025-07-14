package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.dto.FileNamesDTO;
import org.taniwha.dto.MultiFileFilterRequest;
import org.taniwha.service.AnalyticsService;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnalyticsControllerTest {

    private MockMvc mvc;
    private AnalyticsService analyticsService;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        analyticsService = mock(AnalyticsService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new AnalyticsController(analyticsService))
                .build();
    }

    @Test
    void processList_success_returns200AndList() throws Exception {
        FileNamesDTO reqDto = new FileNamesDTO();
        reqDto.setFileNames(List.of("file1.csv", "file2.csv"));

        List<AnalyticsResponseDTO> svcResult = List.of(new AnalyticsResponseDTO("processed"));
        when(analyticsService.processDatasetsOnDisk(reqDto.getFileNames()))
                .thenReturn(svcResult);

        mvc.perform(post("/api/data/processList")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(reqDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").value("processed"));
    }

    @Test
    void processList_serviceThrows_returns500AndErrorMessage() throws Exception {
        FileNamesDTO reqDto = new FileNamesDTO();
        reqDto.setFileNames(Collections.emptyList());

        when(analyticsService.processDatasetsOnDisk(anyList()))
                .thenThrow(new RuntimeException("disk error"));

        mvc.perform(post("/api/data/processList")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(reqDto)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$[0].message")
                        .value("Error processing files: disk error"));
    }

    @Test
    void reprocessList_invalidFeatureType_returns400() throws Exception {
        mvc.perform(post("/api/data/reprocessList")
                        .param("fileName", "f1")
                        .param("featureName", "feat")
                        .param("featureType", "badtype"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid feature type"));

        verifyNoInteractions(analyticsService);
    }

    @Test
    void reprocessList_successfulFuture_returns200AndDto() throws Exception {
        AnalyticsResponseDTO dto = new AnalyticsResponseDTO("done");
        CompletableFuture<AnalyticsResponseDTO> future =
                CompletableFuture.completedFuture(dto);

        when(analyticsService
                .recalculateFeatureAsTypeFromDisk("f1", "feat", "continuous"))
                .thenReturn(future);

        mvc.perform(post("/api/data/reprocessList")
                        .param("fileName", "f1")
                        .param("featureName", "feat")
                        .param("featureType", "continuous"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("done"));
    }

    @Test
    void reprocessList_futureInterrupted_returns500AndInterruptedMessage() throws Exception {
        CompletableFuture<AnalyticsResponseDTO> interruptedFuture =
                new CompletableFuture<>() {
                    @Override
                    public AnalyticsResponseDTO get() throws InterruptedException {
                        throw new InterruptedException();
                    }
                };

        when(analyticsService
                .recalculateFeatureAsTypeFromDisk("f1", "feat", "categorical"))
                .thenReturn(interruptedFuture);

        mvc.perform(post("/api/data/reprocessList")
                        .param("fileName", "f1")
                        .param("featureName", "feat")
                        .param("featureType", "categorical"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value("Thread was interrupted while processing file."));
    }

    @Test
    void reprocessList_futureExecutionException_returns500AndErrorMessage() throws Exception {
        CompletableFuture<AnalyticsResponseDTO> execExceptionFuture =
                new CompletableFuture<>() {
                    @Override
                    public AnalyticsResponseDTO get() throws ExecutionException {
                        throw new ExecutionException("calc failed", null);
                    }
                };
        when(analyticsService
                .recalculateFeatureAsTypeFromDisk("f1", "feat", "continuous"))
                .thenReturn(execExceptionFuture);
        mvc.perform(post("/api/data/reprocessList")
                        .param("fileName", "f1")
                        .param("featureName", "feat")
                        .param("featureType", "continuous"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value("Error processing file: calc failed"));
    }

    @Test
    void filterByNameList_success_returns200AndList() throws Exception {
        MultiFileFilterRequest req = new MultiFileFilterRequest();
        req.setMultipleFileFilters(Collections.emptyList());

        List<AnalyticsResponseDTO> svcResult = List.of(new AnalyticsResponseDTO("filtered"));
        when(analyticsService.filterMultipleFilesByName(anyList()))
                .thenReturn(svcResult);

        mvc.perform(post("/api/data/filterByNameList")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").value("filtered"));
    }

    @Test
    void filterByNameList_serviceThrows_returns500AndErrorMessage() throws Exception {
        MultiFileFilterRequest req = new MultiFileFilterRequest();
        req.setMultipleFileFilters(Collections.emptyList());

        when(analyticsService.filterMultipleFilesByName(anyList()))
                .thenThrow(new RuntimeException("boom"));

        mvc.perform(post("/api/data/filterByNameList")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$[0].message")
                        .value("Error filtering multiple files: boom"));
    }
}
