package org.taniwha.dto;

import org.junit.jupiter.api.Test;
import org.taniwha.service.jobs.CleaningProcessingJobs;

import static org.assertj.core.api.Assertions.assertThat;

class CleaningStatusDTOTest {

    @Test
    void constructor_setsAllFields() {
        CleaningStatusDTO dto = new CleaningStatusDTO(
                "job-1", CleaningProcessingJobs.State.RUNNING, 42, "file.csv", "Working…", "ok");

        assertThat(dto.getJobId()).isEqualTo("job-1");
        assertThat(dto.getState()).isEqualTo(CleaningProcessingJobs.State.RUNNING);
        assertThat(dto.getPercent()).isEqualTo(42);
        assertThat(dto.getCurrentFile()).isEqualTo("file.csv");
        assertThat(dto.getMessage()).isEqualTo("Working…");
        assertThat(dto.getResult()).isEqualTo("ok");
    }

    @Test
    void setters_changeFieldValues() {
        CleaningStatusDTO dto = new CleaningStatusDTO(
                "j0", CleaningProcessingJobs.State.RUNNING, 0, "", "", "");

        dto.setJobId("job-2");
        dto.setState(CleaningProcessingJobs.State.DONE);
        dto.setPercent(100);
        dto.setCurrentFile("out.csv");
        dto.setMessage("Done");
        dto.setResult("Cleaning completed successfully.");

        assertThat(dto.getJobId()).isEqualTo("job-2");
        assertThat(dto.getState()).isEqualTo(CleaningProcessingJobs.State.DONE);
        assertThat(dto.getPercent()).isEqualTo(100);
        assertThat(dto.getCurrentFile()).isEqualTo("out.csv");
        assertThat(dto.getMessage()).isEqualTo("Done");
        assertThat(dto.getResult()).isEqualTo("Cleaning completed successfully.");
    }

    @Test
    void stateValues_areAllAccessible() {
        assertThat(CleaningProcessingJobs.State.values()).containsExactlyInAnyOrder(
                CleaningProcessingJobs.State.RUNNING,
                CleaningProcessingJobs.State.DONE,
                CleaningProcessingJobs.State.ERROR);
    }
}
