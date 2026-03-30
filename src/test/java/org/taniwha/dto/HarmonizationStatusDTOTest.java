package org.taniwha.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HarmonizationStatusDTOTest {

    @Test
    void noArgsConstructor_createsEmptyDto() {
        HarmonizationStatusDTO dto = new HarmonizationStatusDTO();
        assertThat(dto.getJobId()).isNull();
        assertThat(dto.getState()).isNull();
        assertThat(dto.getPercent()).isEqualTo(0);
        assertThat(dto.getCurrentDataset()).isNull();
        assertThat(dto.getMessage()).isNull();
        assertThat(dto.getResult()).isNull();
    }

    @Test
    void allArgsConstructor_setsAllFields() {
        HarmonizationStatusDTO dto = new HarmonizationStatusDTO(
                "j1", HarmonizationStatusDTO.State.RUNNING, 55, "data.csv", "Processing", "result");

        assertThat(dto.getJobId()).isEqualTo("j1");
        assertThat(dto.getState()).isEqualTo(HarmonizationStatusDTO.State.RUNNING);
        assertThat(dto.getPercent()).isEqualTo(55);
        assertThat(dto.getCurrentDataset()).isEqualTo("data.csv");
        assertThat(dto.getMessage()).isEqualTo("Processing");
        assertThat(dto.getResult()).isEqualTo("result");
    }

    @Test
    void setters_changeFieldValues() {
        HarmonizationStatusDTO dto = new HarmonizationStatusDTO();

        dto.setJobId("j2");
        dto.setState(HarmonizationStatusDTO.State.DONE);
        dto.setPercent(100);
        dto.setCurrentDataset("parsed.csv");
        dto.setMessage("Finished");
        dto.setResult("Files processed successfully.");

        assertThat(dto.getJobId()).isEqualTo("j2");
        assertThat(dto.getState()).isEqualTo(HarmonizationStatusDTO.State.DONE);
        assertThat(dto.getPercent()).isEqualTo(100);
        assertThat(dto.getCurrentDataset()).isEqualTo("parsed.csv");
        assertThat(dto.getMessage()).isEqualTo("Finished");
        assertThat(dto.getResult()).isEqualTo("Files processed successfully.");
    }

    @Test
    void stateEnum_containsAllValues() {
        assertThat(HarmonizationStatusDTO.State.values()).containsExactlyInAnyOrder(
                HarmonizationStatusDTO.State.RUNNING,
                HarmonizationStatusDTO.State.DONE,
                HarmonizationStatusDTO.State.ERROR);
    }

    @Test
    void setState_errorState() {
        HarmonizationStatusDTO dto = new HarmonizationStatusDTO();
        dto.setState(HarmonizationStatusDTO.State.ERROR);
        assertThat(dto.getState()).isEqualTo(HarmonizationStatusDTO.State.ERROR);
    }
}
