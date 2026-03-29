package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.dto.*;
import org.taniwha.service.jobs.AnalyticsProcessingJobs;
import org.taniwha.service.AnalyticsAuditService;
import org.taniwha.service.AnalyticsService;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.mockito.Mockito;

class AnalyticsControllerTest {

    private MockMvc mvc;
    private AnalyticsService analyticsService;
    private AnalyticsProcessingJobs jobs;
    private AnalyticsAuditService auditService;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        analyticsService = mock(AnalyticsService.class);
        jobs = mock(AnalyticsProcessingJobs.class);
        auditService = mock(AnalyticsAuditService.class);

        mvc = MockMvcBuilders
                .standaloneSetup(new AnalyticsController(analyticsService, jobs, auditService))
                .build();
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
                .andExpect(jsonPath("$[0].message").value("Error processing files: disk error"));
    }

    @Test
    void processListStatus_unknownJob_returns404() throws Exception {
        when(jobs.getJob("missing")).thenReturn(null);

        mvc.perform(get("/api/data/processList/status/missing"))
                .andExpect(status().isNotFound());
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
        CompletableFuture<AnalyticsResponseDTO> future = CompletableFuture.completedFuture(dto);

        when(analyticsService.recalculateFeatureAsTypeFromDisk("f1", "feat", "continuous"))
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
        CompletableFuture<AnalyticsResponseDTO> interruptedFuture = new CompletableFuture<>() {
            @Override
            public AnalyticsResponseDTO get() throws InterruptedException {
                throw new InterruptedException();
            }
        };

        when(analyticsService.recalculateFeatureAsTypeFromDisk("f1", "feat", "categorical"))
                .thenReturn(interruptedFuture);

        mvc.perform(post("/api/data/reprocessList")
                        .param("fileName", "f1")
                        .param("featureName", "feat")
                        .param("featureType", "categorical"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Thread was interrupted while processing file."));
    }

    @Test
    void reprocessList_futureExecutionException_returns500AndErrorMessage() throws Exception {
        CompletableFuture<AnalyticsResponseDTO> execExceptionFuture = new CompletableFuture<>() {
            @Override
            public AnalyticsResponseDTO get() throws ExecutionException {
                throw new ExecutionException("calc failed", null);
            }
        };

        when(analyticsService.recalculateFeatureAsTypeFromDisk("f1", "feat", "continuous"))
                .thenReturn(execExceptionFuture);

        mvc.perform(post("/api/data/reprocessList")
                        .param("fileName", "f1")
                        .param("featureName", "feat")
                        .param("featureType", "continuous"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Error processing file: calc failed"));
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
                .andExpect(jsonPath("$[0].message").value("Error filtering multiple files: boom"));
    }

    // -------------------------------------------------------------------------
    // processList – huge-job path (returns 202 + jobId)
    // -------------------------------------------------------------------------

    @Test
    void processList_hugeFiles_returns202WithJobId() throws Exception {
        FileNamesDTO reqDto = new FileNamesDTO();
        reqDto.setFileNames(List.of("huge.csv"));

        when(analyticsService.isAnyHugeForDiscovery(reqDto.getFileNames())).thenReturn(true);
        when(jobs.createJob()).thenReturn("job-abc");

        mvc.perform(post("/api/data/processList")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(reqDto)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-abc"))
                .andExpect(jsonPath("$.progress").value(true));

        verify(analyticsService).startDiscoveryJob("job-abc", List.of("huge.csv"));
    }

    // -------------------------------------------------------------------------
    // processListStatus – found job
    // -------------------------------------------------------------------------

    @Test
    void processListStatus_knownJob_returns200WithDto() throws Exception {
        AnalyticsProcessingJobs.JobState s = mock(AnalyticsProcessingJobs.JobState.class);
        ProcessingStatusDTO dto = new ProcessingStatusDTO("job-1", ProcessingStatusDTO.State.RUNNING, 50, "f.csv", null, null);
        when(jobs.getJob("job-1")).thenReturn(s);
        when(jobs.toDto(s, false)).thenReturn(dto);

        mvc.perform(get("/api/data/processList/status/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.percent").value(50));
    }

    // -------------------------------------------------------------------------
    // cancelProcessList
    // -------------------------------------------------------------------------

    @Test
    void cancelProcessList_knownJob_returns200() throws Exception {
        AnalyticsProcessingJobs.JobState s = mock(AnalyticsProcessingJobs.JobState.class);
        ProcessingStatusDTO dto = new ProcessingStatusDTO("job-2", ProcessingStatusDTO.State.CANCELED, 30, null, "Canceled", null);
        when(jobs.getJob("job-2")).thenReturn(s);
        when(jobs.toDto(s, false)).thenReturn(dto);

        mvc.perform(post("/api/data/processList/cancel/job-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELED"));

        verify(jobs).cancel("job-2", "Discovery canceled because the user left the page");
    }

    @Test
    void cancelProcessList_unknownJob_returns404() throws Exception {
        when(jobs.getJob("unknown")).thenReturn(null);

        mvc.perform(post("/api/data/processList/cancel/unknown"))
                .andExpect(status().isNotFound());

        verify(jobs, never()).cancel(any(), any());
    }

    // -------------------------------------------------------------------------
    // processListResult
    // -------------------------------------------------------------------------

    @Test
    void processListResult_unknownJob_returns404() throws Exception {
        when(jobs.getJob("j")).thenReturn(null);

        mvc.perform(get("/api/data/processList/result/j"))
                .andExpect(status().isNotFound());
    }

    @Test
    void processListResult_canceledJob_returns410AndClearsJob() throws Exception {
        AnalyticsProcessingJobs.JobState s = mock(AnalyticsProcessingJobs.JobState.class);
        when(s.getState()).thenReturn(ProcessingStatusDTO.State.CANCELED);
        when(jobs.getJob("j-canceled")).thenReturn(s);

        mvc.perform(get("/api/data/processList/result/j-canceled"))
                .andExpect(status().isGone());

        verify(jobs).clear("j-canceled");
    }

    @Test
    void processListResult_jobStillRunning_returns409() throws Exception {
        AnalyticsProcessingJobs.JobState s = mock(AnalyticsProcessingJobs.JobState.class);
        when(s.getState()).thenReturn(ProcessingStatusDTO.State.RUNNING);
        when(jobs.getJob("j-running")).thenReturn(s);

        mvc.perform(get("/api/data/processList/result/j-running"))
                .andExpect(status().isConflict());

        verify(jobs, never()).clear(any());
    }

    @Test
    void processListResult_doneJobWithResults_returns200AndClearsJob() throws Exception {
        AnalyticsProcessingJobs.JobState s = mock(AnalyticsProcessingJobs.JobState.class);
        when(s.getState()).thenReturn(ProcessingStatusDTO.State.DONE);
        List<AnalyticsResponseDTO> results = List.of(new AnalyticsResponseDTO("r1"));
        when(s.getResults()).thenReturn(results);
        when(jobs.getJob("j-done")).thenReturn(s);

        mvc.perform(get("/api/data/processList/result/j-done"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").value("r1"));

        verify(jobs).clear("j-done");
    }

    @Test
    void processListResult_doneJobNullResults_returnsEmptyList() throws Exception {
        AnalyticsProcessingJobs.JobState s = mock(AnalyticsProcessingJobs.JobState.class);
        when(s.getState()).thenReturn(ProcessingStatusDTO.State.DONE);
        when(s.getResults()).thenReturn(null);
        when(jobs.getJob("j-null")).thenReturn(s);

        mvc.perform(get("/api/data/processList/result/j-null"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        verify(jobs).clear("j-null");
    }
}
