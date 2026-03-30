package org.taniwha.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingStartDTOTest {

    @Test
    void noArgsConstructor_createsDefaultDto() {
        ProcessingStartDTO dto = new ProcessingStartDTO();
        assertThat(dto.getJobId()).isNull();
        assertThat(dto.isProgress()).isFalse();
    }

    @Test
    void allArgsConstructor_setsAllFields() {
        ProcessingStartDTO dto = new ProcessingStartDTO("job-1", true);
        assertThat(dto.getJobId()).isEqualTo("job-1");
        assertThat(dto.isProgress()).isTrue();
    }

    @Test
    void setters_changeFields() {
        ProcessingStartDTO dto = new ProcessingStartDTO();
        dto.setJobId("proc-job");
        dto.setProgress(false);
        assertThat(dto.getJobId()).isEqualTo("proc-job");
        assertThat(dto.isProgress()).isFalse();
    }
}
