package org.taniwha.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HarmonizationStartDTOTest {

    @Test
    void noArgsConstructor_createsDefaultDto() {
        HarmonizationStartDTO dto = new HarmonizationStartDTO();
        assertThat(dto.getJobId()).isNull();
        assertThat(dto.isProgress()).isFalse();
    }

    @Test
    void allArgsConstructor_setsAllFields() {
        HarmonizationStartDTO dto = new HarmonizationStartDTO("job-42", true);
        assertThat(dto.getJobId()).isEqualTo("job-42");
        assertThat(dto.isProgress()).isTrue();
    }

    @Test
    void setters_changeFields() {
        HarmonizationStartDTO dto = new HarmonizationStartDTO();
        dto.setJobId("j-99");
        dto.setProgress(true);
        assertThat(dto.getJobId()).isEqualTo("j-99");
        assertThat(dto.isProgress()).isTrue();
    }
}
