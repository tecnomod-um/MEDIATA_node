package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.taniwha.dto.DataCleaningOptionsDTO;
import org.taniwha.model.FileCategory;
import org.taniwha.security.FileFilter;
import org.taniwha.service.jobs.CleaningProcessingJobs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

/**
 * Integration-style tests for DataCleaningService that exercise async paths
 * (startCleanJob → cleanInPlaceWithProgress → writeCleanedData / stripExt).
 */
class DataCleaningServiceIntegrationTest {

    @TempDir
    Path baseDir;

    private DataCleaningService svc;
    private CleaningProcessingJobs jobs;

    @BeforeEach
    void setUp() {
        FileFilter fileFilter = mock(FileFilter.class);
        doNothing().when(fileFilter).validate(any(Path.class));

        FileService fileService = new FileService(fileFilter, baseDir.toString());
        DataProcessingService dataProcessingService = new DataProcessingService(fileFilter);
        jobs = new CleaningProcessingJobs();
        svc = new DataCleaningService(fileService, dataProcessingService, jobs);
    }

    private Path createDatasetFile(String name, String content) throws Exception {
        Path ds = baseDir.resolve("datasets");
        Files.createDirectories(ds);
        Path file = ds.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private void waitForJobDone(String jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (jobs.getJob(jobId).getState() == CleaningProcessingJobs.State.RUNNING
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    // startCleanJob – async path

    @Test
    void startCleanJob_removeDuplicates_completesWithDoneState() throws Exception {
        createDatasetFile("dup.csv", "a;b\n1;x\n1;x\n2;y\n");

        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        opts.setRemoveDuplicates(true);

        String jobId = jobs.createJob();
        svc.startCleanJob(jobId, FileCategory.DATASETS, "dup.csv", opts);

        waitForJobDone(jobId);

        assertThat(jobs.getJob(jobId).getState()).isEqualTo(CleaningProcessingJobs.State.DONE);
        assertThat(jobs.getJob(jobId).getResult()).isNotNull();
    }

    @Test
    void startCleanJob_noOptions_completesWithNoCleaningMessage() throws Exception {
        createDatasetFile("plain.csv", "a;b\n1;x\n");

        String jobId = jobs.createJob();
        svc.startCleanJob(jobId, FileCategory.DATASETS, "plain.csv", null);

        waitForJobDone(jobId);

        assertThat(jobs.getJob(jobId).getState()).isEqualTo(CleaningProcessingJobs.State.DONE);
    }

    @Test
    void startCleanJob_missingFile_setsErrorState() throws Exception {
        // No file created — the service should fail gracefully
        Path ds = baseDir.resolve("datasets");
        Files.createDirectories(ds);

        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        opts.setRemoveDuplicates(true);

        String jobId = jobs.createJob();
        svc.startCleanJob(jobId, FileCategory.DATASETS, "nonexistent.csv", opts);

        waitForJobDone(jobId);

        assertThat(jobs.getJob(jobId).getState()).isEqualTo(CleaningProcessingJobs.State.ERROR);
    }

    @Test
    void startCleanJob_caseStandardization_completesSuccessfully() throws Exception {
        createDatasetFile("case.csv", "name;value\nalice;hello world\nBOB;TEST\n");

        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        opts.setStandardizeCase(true);
        opts.setCaseMode("lower");

        String jobId = jobs.createJob();
        svc.startCleanJob(jobId, FileCategory.DATASETS, "case.csv", opts);

        waitForJobDone(jobId);

        assertThat(jobs.getJob(jobId).getState()).isEqualTo(CleaningProcessingJobs.State.DONE);
    }

    @Test
    void startCleanJob_numericColumns_extractsAndStandardizes() throws Exception {
        createDatasetFile("nums.csv", "name;score\nAlice;10.5\nBob;20.0\nCarol;15.0\n");

        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        opts.setStandardizeNumeric(true);
        opts.setNumericColumns(List.of("file:::score"));
        opts.setNumericMode("double");

        String jobId = jobs.createJob();
        svc.startCleanJob(jobId, FileCategory.DATASETS, "nums.csv", opts);

        waitForJobDone(jobId);

        assertThat(jobs.getJob(jobId).getState()).isEqualTo(CleaningProcessingJobs.State.DONE);
    }

    // cleanInPlaceWithProgress – direct call (synchronous)

    @Test
    void cleanInPlaceWithProgress_nullCategory_throwsNullPointer() {
        String jobId = jobs.createJob();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> svc.cleanInPlaceWithProgress(jobId, null, "file.csv", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void cleanInPlaceWithProgress_emptyName_throwsIllegalArgument() {
        String jobId = jobs.createJob();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> svc.cleanInPlaceWithProgress(jobId, FileCategory.DATASETS, "  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name is required");
    }
}
