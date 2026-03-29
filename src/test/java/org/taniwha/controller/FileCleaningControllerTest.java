package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.dto.DataCleaningOptionsDTO;
import org.taniwha.model.FileCategory;
import org.taniwha.service.DataCleaningService;
import org.taniwha.service.jobs.CleaningProcessingJobs;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileCleaningControllerTest {

    private MockMvc mvc;
    private DataCleaningService dataCleaningService;
    private CleaningProcessingJobs cleaningJobs;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        dataCleaningService = mock(DataCleaningService.class);
        cleaningJobs = mock(CleaningProcessingJobs.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new FileCleaningController(dataCleaningService, cleaningJobs))
                .build();
    }

    // -------------------------------------------------------------------------
    // POST /api/files/clean
    // -------------------------------------------------------------------------

    @Test
    void cleanFile_invokesServiceAndReturns200() throws Exception {
        mvc.perform(post("/api/files/clean")
                        .param("category", "DATASETS")
                        .param("name", "data.csv"))
                .andExpect(status().isOk());

        verify(dataCleaningService).cleanInPlace(eq(FileCategory.DATASETS), eq("data.csv"), isNull());
    }

    @Test
    void cleanFile_withOptions_passesOptionsToService() throws Exception {
        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        mvc.perform(post("/api/files/clean")
                        .param("category", "DATASETS")
                        .param("name", "data.csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(opts)))
                .andExpect(status().isOk());

        verify(dataCleaningService).cleanInPlace(eq(FileCategory.DATASETS), eq("data.csv"), any(DataCleaningOptionsDTO.class));
    }

    // -------------------------------------------------------------------------
    // POST /api/files/clean/start
    // -------------------------------------------------------------------------

    @Test
    void startCleanFile_returns202WithJobId() throws Exception {
        when(cleaningJobs.createJob()).thenReturn("job-1");

        mvc.perform(post("/api/files/clean/start")
                        .param("category", "DATASETS")
                        .param("name", "data.csv"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.accepted").value(true));

        verify(dataCleaningService).startCleanJob(eq("job-1"), eq(FileCategory.DATASETS), eq("data.csv"), isNull());
    }

    @Test
    void startCleanFile_withOptions_passesOptionsToService() throws Exception {
        when(cleaningJobs.createJob()).thenReturn("job-2");
        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();

        mvc.perform(post("/api/files/clean/start")
                        .param("category", "FHIR_MAPPINGS")
                        .param("name", "map.json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(opts)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-2"));

        verify(dataCleaningService).startCleanJob(eq("job-2"), eq(FileCategory.FHIR_MAPPINGS), eq("map.json"), any(DataCleaningOptionsDTO.class));
    }

    // -------------------------------------------------------------------------
    // GET /api/files/clean/status/{jobId}
    // -------------------------------------------------------------------------

    @Test
    void getCleanStatus_unknownJob_returns404() throws Exception {
        when(cleaningJobs.getJob("missing")).thenReturn(null);

        mvc.perform(get("/api/files/clean/status/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCleanStatus_knownJob_returns200WithFields() throws Exception {
        CleaningProcessingJobs.JobState state = makeJobState("job-3", CleaningProcessingJobs.State.RUNNING, 42, "current.csv", "Working");
        when(cleaningJobs.getJob("job-3")).thenReturn(state);

        mvc.perform(get("/api/files/clean/status/job-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-3"))
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.percent").value(42))
                .andExpect(jsonPath("$.currentFile").value("current.csv"))
                .andExpect(jsonPath("$.message").value("Working"));
    }

    @Test
    void getCleanStatus_nullCurrentFileAndMessage_returnsEmptyStrings() throws Exception {
        CleaningProcessingJobs.JobState state = makeJobState("job-4", CleaningProcessingJobs.State.DONE, 100, null, null);
        when(cleaningJobs.getJob("job-4")).thenReturn(state);

        mvc.perform(get("/api/files/clean/status/job-4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentFile").value(""))
                .andExpect(jsonPath("$.message").value(""));
    }

    // -------------------------------------------------------------------------
    // GET /api/files/clean/result/{jobId}
    // -------------------------------------------------------------------------

    @Test
    void getCleanResult_unknownJob_returns404() throws Exception {
        when(cleaningJobs.getJob("gone")).thenReturn(null);

        mvc.perform(get("/api/files/clean/result/gone"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCleanResult_jobInErrorState_returns500AndClearsJob() throws Exception {
        CleaningProcessingJobs.JobState state = makeJobState("job-5", CleaningProcessingJobs.State.ERROR, 0, null, "Something went wrong");
        when(cleaningJobs.getJob("job-5")).thenReturn(state);

        mvc.perform(get("/api/files/clean/result/job-5"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Something went wrong"));

        verify(cleaningJobs).clear("job-5");
    }

    @Test
    void getCleanResult_errorStateNullMessage_usesDefaultMessage() throws Exception {
        CleaningProcessingJobs.JobState state = makeJobState("job-6", CleaningProcessingJobs.State.ERROR, 0, null, null);
        when(cleaningJobs.getJob("job-6")).thenReturn(state);

        mvc.perform(get("/api/files/clean/result/job-6"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Cleaning failed."));

        verify(cleaningJobs).clear("job-6");
    }

    @Test
    void getCleanResult_jobStillRunning_returns409() throws Exception {
        CleaningProcessingJobs.JobState state = makeJobState("job-7", CleaningProcessingJobs.State.RUNNING, 50, null, null);
        when(cleaningJobs.getJob("job-7")).thenReturn(state);

        mvc.perform(get("/api/files/clean/result/job-7"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cleaning job is not finished yet."));

        verify(cleaningJobs, never()).clear(any());
    }

    @Test
    void getCleanResult_jobDoneWithResult_returns200AndClearsJob() throws Exception {
        CleaningProcessingJobs.JobState state = makeJobState("job-8", CleaningProcessingJobs.State.DONE, 100, null, null);
        when(state.getResult()).thenReturn("All clean!");
        when(cleaningJobs.getJob("job-8")).thenReturn(state);

        mvc.perform(get("/api/files/clean/result/job-8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("All clean!"));

        verify(cleaningJobs).clear("job-8");
    }

    @Test
    void getCleanResult_jobDoneNullResult_usesDefaultMessage() throws Exception {
        CleaningProcessingJobs.JobState state = makeJobState("job-9", CleaningProcessingJobs.State.DONE, 100, null, null);
        // result is null
        when(cleaningJobs.getJob("job-9")).thenReturn(state);

        mvc.perform(get("/api/files/clean/result/job-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Cleaning completed successfully."));

        verify(cleaningJobs).clear("job-9");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private CleaningProcessingJobs.JobState makeJobState(String jobId,
                                                          CleaningProcessingJobs.State state,
                                                          int percent,
                                                          String currentFile,
                                                          String message) {
        CleaningProcessingJobs.JobState js = mock(CleaningProcessingJobs.JobState.class);
        java.util.concurrent.atomic.AtomicInteger pct = new java.util.concurrent.atomic.AtomicInteger(percent);
        when(js.getJobId()).thenReturn(jobId);
        when(js.getState()).thenReturn(state);
        when(js.getPercent()).thenReturn(pct);
        when(js.getCurrentFile()).thenReturn(currentFile);
        when(js.getMessage()).thenReturn(message);
        when(js.getResult()).thenReturn(null);
        return js;
    }
}
