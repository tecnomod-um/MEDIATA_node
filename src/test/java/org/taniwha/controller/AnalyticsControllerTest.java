package org.taniwha.controller;

import org.taniwha.service.jobs.AnalyticsJobState;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.dto.*;
import org.taniwha.service.jobs.AnalyticsProcessingJobs;
import org.taniwha.service.AnalyticsService;
import org.taniwha.service.DatasetElementExtractionService;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnalyticsControllerTest {

    private MockMvc mvc;
    private AnalyticsService analyticsService;
    private AnalyticsProcessingJobs jobs;
    private DatasetElementExtractionService datasetElementExtractionService;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        analyticsService = mock(AnalyticsService.class);
        jobs = mock(AnalyticsProcessingJobs.class);
        datasetElementExtractionService = mock(DatasetElementExtractionService.class);

        mvc = MockMvcBuilders
                .standaloneSetup(new AnalyticsController(analyticsService, jobs, datasetElementExtractionService))
                .build();
    }

    @Test
    void extractElements_success_returns200AndElements() throws Exception {
        FileNamesDTO reqDto = new FileNamesDTO();
        reqDto.setFileNames(List.of("file1.csv"));

        when(datasetElementExtractionService.extractDatasetElements(reqDto.getFileNames()))
                .thenReturn(List.of(new DatasetElementsDTO(
                        "file1.csv",
                        List.of(new DatasetElementDTO("age", List.of("integer", "min:1", "max:90")))
                )));

        mvc.perform(post("/api/data/extractElements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(reqDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileName").value("file1.csv"))
                .andExpect(jsonPath("$[0].elements[0].column").value("age"))
                .andExpect(jsonPath("$[0].elements[0].values[0]").value("integer"));
    }

    @Test
    void processList_success_nonHuge_returns200AndList() throws Exception {
        FileNamesDTO reqDto = new FileNamesDTO();
        reqDto.setFileNames(List.of("file1.csv", "file2.csv"));

        when(analyticsService.isAnyHugeForDiscovery(reqDto.getFileNames()))
                .thenReturn(false);

        List<AnalyticsResponseDTO> svcResult = List.of(new AnalyticsResponseDTO("processed"));
        when(analyticsService.processDatasetsOnDisk(reqDto.getFileNames()))
                .thenReturn(svcResult);

        mvc.perform(post("/api/data/processList")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(reqDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").value("processed"));

        verify(jobs, never()).createJob();
        verify(analyticsService, never()).startDiscoveryJob(anyString(), anyList());
    }

    @Test
    void processList_serviceThrows_returns500AndErrorMessage() throws Exception {
        FileNamesDTO reqDto = new FileNamesDTO();
        reqDto.setFileNames(Collections.emptyList());

        when(analyticsService.isAnyHugeForDiscovery(anyList()))
                .thenThrow(new RuntimeException("disk error"));

        mvc.perform(post("/api/data/processList")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(reqDto)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$[0].message").value("Error processing statistics."));
    }

    @Test
    void processListStatus_unknownJob_returns404() throws Exception {
        when(jobs.getJob("missing")).thenReturn(null);

        mvc.perform(get("/api/data/processList/status/missing"))
                .andExpect(status().isNotFound());
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
                .andExpect(jsonPath("$[0].message").value("Error processing statistics."));
    }

    @Test
    void processList_hugeFiles_returns202WithJobId() throws Exception {
        FileNamesDTO reqDto = new FileNamesDTO();
        reqDto.setFileNames(List.of("big.csv"));

        when(analyticsService.isAnyHugeForDiscovery(anyList())).thenReturn(true);
        when(jobs.createJob()).thenReturn("job-42");

        mvc.perform(post("/api/data/processList")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(reqDto)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-42"))
                .andExpect(jsonPath("$.progress").value(true));

        verify(analyticsService, times(1)).startDiscoveryJob(eq("job-42"), anyList());
    }

    @Test
    void processListStatus_knownJob_returnsStatusDto() throws Exception {
        AnalyticsJobState state = mock(AnalyticsJobState.class);
        when(jobs.getJob("job-1")).thenReturn(state);

        ProcessingStatusDTO dto = new ProcessingStatusDTO("job-1", ProcessingStatusDTO.State.RUNNING, 55, "file.csv", null, null);
        when(jobs.toDto(state, false)).thenReturn(dto);

        mvc.perform(get("/api/data/processList/status/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.percent").value(55));
    }

    @Test
    void cancelProcessList_unknownJob_returns404() throws Exception {
        when(jobs.getJob("unknown")).thenReturn(null);

        mvc.perform(post("/api/data/processList/cancel/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelProcessList_knownJob_cancelsAndReturnsDto() throws Exception {
        AnalyticsJobState state = mock(AnalyticsJobState.class);
        when(jobs.getJob("job-99")).thenReturn(state);

        ProcessingStatusDTO dto = new ProcessingStatusDTO(
                "job-99", ProcessingStatusDTO.State.CANCELED, 0, null, "Canceled", null);
        when(jobs.toDto(state, false)).thenReturn(dto);

        mvc.perform(post("/api/data/processList/cancel/job-99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELED"));

        verify(jobs, times(1)).cancel(eq("job-99"), anyString());
    }

    @Test
    void processListResult_unknownJob_returns404() throws Exception {
        when(jobs.getJob("x")).thenReturn(null);

        mvc.perform(get("/api/data/processList/result/x"))
                .andExpect(status().isNotFound());
    }

    @Test
    void processListResult_canceledState_returns410() throws Exception {
        AnalyticsJobState state = mock(AnalyticsJobState.class);
        when(state.getState()).thenReturn(ProcessingStatusDTO.State.CANCELED);
        when(jobs.getJob("j")).thenReturn(state);

        mvc.perform(get("/api/data/processList/result/j"))
                .andExpect(status().isGone());

        verify(jobs, times(1)).clear("j");
    }

    @Test
    void processListResult_notDoneState_returns409() throws Exception {
        AnalyticsJobState state = mock(AnalyticsJobState.class);
        when(state.getState()).thenReturn(ProcessingStatusDTO.State.RUNNING);
        when(jobs.getJob("j")).thenReturn(state);

        mvc.perform(get("/api/data/processList/result/j"))
                .andExpect(status().isConflict());
    }

    @Test
    void processListResult_doneState_returns200WithResults() throws Exception {
        AnalyticsJobState state = mock(AnalyticsJobState.class);
        when(state.getState()).thenReturn(ProcessingStatusDTO.State.DONE);
        when(state.getResults()).thenReturn(List.of(new AnalyticsResponseDTO("r1")));
        when(jobs.getJob("j")).thenReturn(state);

        mvc.perform(get("/api/data/processList/result/j"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").value("r1"));

        verify(jobs, times(1)).clear("j");
    }

    @Test
    void processListResult_doneStateNullResults_returns200WithEmptyList() throws Exception {
        AnalyticsJobState state = mock(AnalyticsJobState.class);
        when(state.getState()).thenReturn(ProcessingStatusDTO.State.DONE);
        when(state.getResults()).thenReturn(null);
        when(jobs.getJob("j")).thenReturn(state);

        mvc.perform(get("/api/data/processList/result/j"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void hugeTypedRefreshUsesBackgroundJobAndPreservesRequest() throws Exception {
        when(analyticsService.isAnyHugeForDiscovery(List.of("large.csv"))).thenReturn(true);
        when(jobs.createJob()).thenReturn("refresh-job");
        mvc.perform(post("/api/data/filterByNameList").contentType(MediaType.APPLICATION_JSON)
                .content("{\"multipleFileFilters\":[{\"fileName\":\"large.csv\",\"featureTypes\":{\"Cluster\":\"categorical\"}}]}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").value("refresh-job"));
        verify(analyticsService).startFilteredDiscoveryJob(eq("refresh-job"), argThat(files ->
                "categorical".equals(files.get(0).getFeatureTypes().get("Cluster"))));
        verify(analyticsService, never()).filterMultipleFilesByName(anyList());
    }

    @Test
    void busyBackgroundRefreshReturnsRetryableStatus() throws Exception {
        when(analyticsService.isAnyHugeForDiscovery(anyList())).thenReturn(true);
        when(jobs.createJob()).thenReturn("busy");
        doThrow(new java.util.concurrent.RejectedExecutionException()).when(analyticsService)
                .startFilteredDiscoveryJob(eq("busy"), anyList());
        mvc.perform(post("/api/data/filterByNameList").contentType(MediaType.APPLICATION_JSON)
                .content("{\"multipleFileFilters\":[{\"fileName\":\"large.csv\"}]}"))
                .andExpect(status().isServiceUnavailable());
    }
}
