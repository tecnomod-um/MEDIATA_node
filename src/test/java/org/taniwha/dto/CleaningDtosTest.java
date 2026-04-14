package org.taniwha.dto;

import org.junit.jupiter.api.Test;
import org.taniwha.service.jobs.CleaningProcessingJobs;

import static org.assertj.core.api.Assertions.assertThat;

class CleaningDtosTest {

    @Test
    void cleaningStatusDTO_gettersAndSetters() {
        CleaningStatusDTO dto = new CleaningStatusDTO(
                "job-1",
                CleaningProcessingJobs.State.RUNNING,
                50,
                "file.csv",
                "Cleaning...",
                null
        );

        assertThat(dto.getJobId()).isEqualTo("job-1");
        assertThat(dto.getState()).isEqualTo(CleaningProcessingJobs.State.RUNNING);
        assertThat(dto.getPercent()).isEqualTo(50);
        assertThat(dto.getCurrentFile()).isEqualTo("file.csv");
        assertThat(dto.getMessage()).isEqualTo("Cleaning...");
        assertThat(dto.getResult()).isNull();

        dto.setJobId("job-2");
        dto.setState(CleaningProcessingJobs.State.DONE);
        dto.setPercent(100);
        dto.setCurrentFile(null);
        dto.setMessage("Done");
        dto.setResult("Cleaned OK");

        assertThat(dto.getJobId()).isEqualTo("job-2");
        assertThat(dto.getState()).isEqualTo(CleaningProcessingJobs.State.DONE);
        assertThat(dto.getPercent()).isEqualTo(100);
        assertThat(dto.getCurrentFile()).isNull();
        assertThat(dto.getMessage()).isEqualTo("Done");
        assertThat(dto.getResult()).isEqualTo("Cleaned OK");
    }

    @Test
    void cleaningStartDTO_gettersMatchConstructor() {
        CleaningStartDTO dto = new CleaningStartDTO("abc-123", true);

        assertThat(dto.getJobId()).isEqualTo("abc-123");
        assertThat(dto.isAccepted()).isTrue();
    }

    @Test
    void processingStartDTO_gettersSetters() {
        ProcessingStartDTO dto = new ProcessingStartDTO();
        dto.setJobId("job-9");
        dto.setProgress(false);

        assertThat(dto.getJobId()).isEqualTo("job-9");
        assertThat(dto.isProgress()).isFalse();

        ProcessingStartDTO dto2 = new ProcessingStartDTO("job-7", true);
        assertThat(dto2.getJobId()).isEqualTo("job-7");
        assertThat(dto2.isProgress()).isTrue();
    }

    @Test
    void harmonizationStartDTO_gettersAndSetters() {
        HarmonizationStartDTO dto = new HarmonizationStartDTO("h-1", true);
        assertThat(dto.getJobId()).isEqualTo("h-1");
        assertThat(dto.isProgress()).isTrue();
    }

    @Test
    void harmonizationStatusDTO_setters() {
        HarmonizationStatusDTO dto = new HarmonizationStatusDTO(
                "id-1",
                HarmonizationStatusDTO.State.RUNNING,
                25,
                "dataset.csv",
                "Processing...",
                null
        );

        assertThat(dto.getJobId()).isEqualTo("id-1");
        assertThat(dto.getState()).isEqualTo(HarmonizationStatusDTO.State.RUNNING);
        assertThat(dto.getPercent()).isEqualTo(25);
        assertThat(dto.getCurrentDataset()).isEqualTo("dataset.csv");
        assertThat(dto.getMessage()).isEqualTo("Processing...");
        assertThat(dto.getResult()).isNull();
    }
}
