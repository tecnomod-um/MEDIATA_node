package org.taniwha.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CleaningStartDTOTest {

    @Test
    void constructor_setsAllFields() {
        CleaningStartDTO dto = new CleaningStartDTO("job-abc", true);

        assertThat(dto.getJobId()).isEqualTo("job-abc");
        assertThat(dto.isAccepted()).isTrue();
    }

    @Test
    void constructor_withFalseAccepted() {
        CleaningStartDTO dto = new CleaningStartDTO("job-xyz", false);

        assertThat(dto.getJobId()).isEqualTo("job-xyz");
        assertThat(dto.isAccepted()).isFalse();
    }
}
