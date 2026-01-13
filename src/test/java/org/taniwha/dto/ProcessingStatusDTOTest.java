package org.taniwha.dto;

import org.junit.jupiter.api.Test;
import org.taniwha.dto.ProcessingStatusDTO.State;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ProcessingStatusDTOTest {

    @Test
    void constructor_withAllParameters_shouldSetFields() {
        List<AnalyticsResponseDTO> results = Arrays.asList(new AnalyticsResponseDTO());
        
        ProcessingStatusDTO dto = new ProcessingStatusDTO(
                "job123",
                State.DONE,
                100,
                "file.csv",
                "Success",
                results
        );

        assertThat(dto.getJobId()).isEqualTo("job123");
        assertThat(dto.getState()).isEqualTo(State.DONE);
        assertThat(dto.getPercent()).isEqualTo(100);
        assertThat(dto.getCurrentFile()).isEqualTo("file.csv");
        assertThat(dto.getMessage()).isEqualTo("Success");
        assertThat(dto.getResults()).isEqualTo(results);
    }

    @Test
    void constructor_withNullResults_shouldAllowNull() {
        ProcessingStatusDTO dto = new ProcessingStatusDTO(
                "job456",
                State.RUNNING,
                50,
                "data.xlsx",
                null,
                null
        );

        assertThat(dto.getJobId()).isEqualTo("job456");
        assertThat(dto.getState()).isEqualTo(State.RUNNING);
        assertThat(dto.getPercent()).isEqualTo(50);
        assertThat(dto.getCurrentFile()).isEqualTo("data.xlsx");
        assertThat(dto.getMessage()).isNull();
        assertThat(dto.getResults()).isNull();
    }

    @Test
    void state_allValues_shouldBeAccessible() {
        assertThat(State.RUNNING).isNotNull();
        assertThat(State.DONE).isNotNull();
        assertThat(State.ERROR).isNotNull();
        
        // Verify valueOf works
        assertThat(State.valueOf("RUNNING")).isEqualTo(State.RUNNING);
        assertThat(State.valueOf("DONE")).isEqualTo(State.DONE);
        assertThat(State.valueOf("ERROR")).isEqualTo(State.ERROR);
    }

    @Test
    void state_values_shouldReturnAllStates() {
        State[] states = State.values();
        
        assertThat(states).hasSize(3);
        assertThat(states).contains(State.RUNNING, State.DONE, State.ERROR);
    }

    @Test
    void setters_shouldUpdateFields() {
        ProcessingStatusDTO dto = new ProcessingStatusDTO(
                "job1",
                State.RUNNING,
                0,
                null,
                null,
                null
        );

        dto.setJobId("job2");
        dto.setState(State.DONE);
        dto.setPercent(100);
        dto.setCurrentFile("test.csv");
        dto.setMessage("Complete");
        List<AnalyticsResponseDTO> results = Arrays.asList(new AnalyticsResponseDTO());
        dto.setResults(results);

        assertThat(dto.getJobId()).isEqualTo("job2");
        assertThat(dto.getState()).isEqualTo(State.DONE);
        assertThat(dto.getPercent()).isEqualTo(100);
        assertThat(dto.getCurrentFile()).isEqualTo("test.csv");
        assertThat(dto.getMessage()).isEqualTo("Complete");
        assertThat(dto.getResults()).isEqualTo(results);
    }
}
