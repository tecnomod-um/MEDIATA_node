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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileCleaningControllerTest {

    private MockMvc mvc;
    private DataCleaningService dataCleaningService;
    private CleaningProcessingJobs cleaningJobs;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        dataCleaningService = mock(DataCleaningService.class);
        cleaningJobs = new CleaningProcessingJobs();

        mvc = MockMvcBuilders
                .standaloneSetup(new FileCleaningController(dataCleaningService, cleaningJobs))
                .build();
    }

    // -----------------------------------------------------------------------
    // POST /api/files/clean
    // -----------------------------------------------------------------------

    @Test
    void cleanFile_callsServiceAndReturns200() throws Exception {
        doNothing().when(dataCleaningService).cleanInPlace(any(), any(), any());

        mvc.perform(post("/api/files/clean")
                        .param("category", "DATASETS")
                        .param("name", "file.csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(dataCleaningService, times(1))
                .cleanInPlace(eq(FileCategory.DATASETS), eq("file.csv"), any());
    }

    @Test
    void cleanFile_noBody_callsServiceWithNullOptions() throws Exception {
        doNothing().when(dataCleaningService).cleanInPlace(any(), any(), isNull());

        mvc.perform(post("/api/files/clean")
                        .param("category", "DATASETS")
                        .param("name", "file.csv"))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // POST /api/files/clean/start
    // -----------------------------------------------------------------------

    @Test
    void startCleanFile_returns202WithJobId() throws Exception {
        doNothing().when(dataCleaningService).startCleanJob(anyString(), any(), any(), any());

        mvc.perform(post("/api/files/clean/start")
                        .param("category", "DATASETS")
                        .param("name", "file.csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new DataCleaningOptionsDTO())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isString())
                .andExpect(jsonPath("$.accepted").value(true));

        verify(dataCleaningService, times(1))
                .startCleanJob(anyString(), eq(FileCategory.DATASETS), eq("file.csv"), any());
    }

    // -----------------------------------------------------------------------
    // GET /api/files/clean/status/{jobId}
    // -----------------------------------------------------------------------

    @Test
    void getCleanStatus_unknownJob_returns404() throws Exception {
        mvc.perform(get("/api/files/clean/status/unknown-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCleanStatus_runningJob_returns200WithState() throws Exception {
        String jobId = cleaningJobs.createJob();
        cleaningJobs.update(jobId, 42, "data.csv", "Cleaning...");

        mvc.perform(get("/api/files/clean/status/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.percent").value(42))
                .andExpect(jsonPath("$.currentFile").value("data.csv"))
                .andExpect(jsonPath("$.message").value("Cleaning..."));
    }

    @Test
    void getCleanStatus_nullCurrentFileAndMessage_returnsEmptyStrings() throws Exception {
        String jobId = cleaningJobs.createJob();
        // No update — currentFile and message are null

        mvc.perform(get("/api/files/clean/status/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentFile").value(""))
                .andExpect(jsonPath("$.message").value(""));
    }

    // -----------------------------------------------------------------------
    // GET /api/files/clean/result/{jobId}
    // -----------------------------------------------------------------------

    @Test
    void getCleanResult_unknownJob_returns404() throws Exception {
        mvc.perform(get("/api/files/clean/result/unknown-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCleanResult_errorState_returns500AndMessage() throws Exception {
        String jobId = cleaningJobs.createJob();
        cleaningJobs.fail(jobId, "Something went wrong");

        mvc.perform(get("/api/files/clean/result/{jobId}", jobId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Something went wrong"));
    }

    @Test
    void getCleanResult_errorStateNullMessage_returnsDefaultMessage() throws Exception {
        String jobId = cleaningJobs.createJob();
        cleaningJobs.fail(jobId, null);

        mvc.perform(get("/api/files/clean/result/{jobId}", jobId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Cleaning failed."));
    }

    @Test
    void getCleanResult_notDoneState_returns409() throws Exception {
        String jobId = cleaningJobs.createJob();
        // still RUNNING

        mvc.perform(get("/api/files/clean/result/{jobId}", jobId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cleaning job is not finished yet."));
    }

    @Test
    void getCleanResult_doneState_returns200WithResult() throws Exception {
        String jobId = cleaningJobs.createJob();
        cleaningJobs.complete(jobId, "Cleaning completed successfully.");

        mvc.perform(get("/api/files/clean/result/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Cleaning completed successfully."));
    }

    @Test
    void getCleanResult_doneStateNullResult_returnsDefaultMessage() throws Exception {
        String jobId = cleaningJobs.createJob();
        // Force DONE state with null result by directly using job state
        CleaningProcessingJobs.JobState s = cleaningJobs.getJob(jobId);
        s.setState(CleaningProcessingJobs.State.DONE);
        s.setResult(null);

        mvc.perform(get("/api/files/clean/result/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Cleaning completed successfully."));
    }
}
