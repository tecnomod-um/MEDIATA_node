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
        
        assertThat(states).hasSize(4);
        assertThat(states).contains(State.RUNNING, State.DONE, State.ERROR, State.CANCELED);
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

    @Test
    void constructor_withZeroPercent_shouldRepresentJobStart() {
        ProcessingStatusDTO dto = new ProcessingStatusDTO(
                "job789",
                State.RUNNING,
                0,
                "initialFile.csv",
                "Starting processing",
                null
        );

        assertThat(dto.getPercent()).isEqualTo(0);
        assertThat(dto.getState()).isEqualTo(State.RUNNING);
        assertThat(dto.getResults()).isNull();
    }

    @Test
    void constructor_with100Percent_shouldRepresentCompletion() {
        List<AnalyticsResponseDTO> results = Arrays.asList(new AnalyticsResponseDTO());
        ProcessingStatusDTO dto = new ProcessingStatusDTO(
                "job999",
                State.DONE,
                100,
                "finalFile.csv",
                "Processing complete",
                results
        );

        assertThat(dto.getPercent()).isEqualTo(100);
        assertThat(dto.getState()).isEqualTo(State.DONE);
        assertThat(dto.getResults()).isNotNull();
    }

    @Test
    void state_error_shouldRepresentFailedProcessing() {
        ProcessingStatusDTO dto = new ProcessingStatusDTO(
                "job404",
                State.ERROR,
                50,
                "problemFile.csv",
                "Error: File corrupted",
                null
        );

        assertThat(dto.getState()).isEqualTo(State.ERROR);
        assertThat(dto.getMessage()).contains("Error");
        assertThat(dto.getResults()).isNull();
    }

    @Test
    void setPercent_withProgressionValues_shouldReflectProcessingStages() {
        ProcessingStatusDTO dto = new ProcessingStatusDTO(
                "jobProgress",
                State.RUNNING,
                0,
                "file.csv",
                null,
                null
        );

        // Simulate processing progression
        dto.setPercent(25);
        assertThat(dto.getPercent()).isEqualTo(25);
        
        dto.setPercent(50);
        assertThat(dto.getPercent()).isEqualTo(50);
        
        dto.setPercent(75);
        assertThat(dto.getPercent()).isEqualTo(75);
        
        dto.setPercent(100);
        dto.setState(State.DONE);
        assertThat(dto.getPercent()).isEqualTo(100);
        assertThat(dto.getState()).isEqualTo(State.DONE);
    }

    @Test
    void constructor_withMultipleResults_shouldStoreAllResults() {
        List<AnalyticsResponseDTO> multipleResults = Arrays.asList(
                new AnalyticsResponseDTO(),
                new AnalyticsResponseDTO(),
                new AnalyticsResponseDTO()
        );
        
        ProcessingStatusDTO dto = new ProcessingStatusDTO(
                "jobMulti",
                State.DONE,
                100,
                "data.xlsx",
                "Multiple analytics completed",
                multipleResults
        );

        assertThat(dto.getResults()).hasSize(3);
        assertThat(dto.getResults()).isEqualTo(multipleResults);
    }

    @Test
    void setCurrentFile_withDifferentFileNames_shouldUpdateCorrectly() {
        ProcessingStatusDTO dto = new ProcessingStatusDTO(
                "jobFiles",
                State.RUNNING,
                0,
                null,
                null,
                null
        );

        dto.setCurrentFile("file1.csv");
        assertThat(dto.getCurrentFile()).isEqualTo("file1.csv");

        dto.setCurrentFile("file2.xlsx");
        assertThat(dto.getCurrentFile()).isEqualTo("file2.xlsx");

        dto.setCurrentFile("file3.ttl");
        assertThat(dto.getCurrentFile()).isEqualTo("file3.ttl");
    }

    @Test
    void setState_transitionFromRunningToError_shouldBeValid() {
        ProcessingStatusDTO dto = new ProcessingStatusDTO(
                "jobTransition",
                State.RUNNING,
                45,
                "file.csv",
                "Processing",
                null
        );

        dto.setState(State.ERROR);
        dto.setMessage("Unexpected error occurred");

        assertThat(dto.getState()).isEqualTo(State.ERROR);
        assertThat(dto.getMessage()).contains("error");
    }

    @Test
    void setMessage_withDetailedErrorMessages_shouldPreserveDetails() {
        ProcessingStatusDTO dto = new ProcessingStatusDTO(
                "jobError",
                State.ERROR,
                0,
                "corrupt.csv",
                null,
                null
        );

        String detailedError = "Error at line 542: Invalid data format. Expected numeric value, got 'N/A'";
        dto.setMessage(detailedError);

        assertThat(dto.getMessage()).isEqualTo(detailedError);
        assertThat(dto.getMessage()).contains("line 542");
        assertThat(dto.getMessage()).contains("Invalid data format");
    }
}
