package org.taniwha.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class NodeValidationResponseDTOTest {

    @Test
    void constructor_withToken_shouldSetJwtNodeToken() {
        String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test.token";
        
        NodeValidationResponseDTO dto = new NodeValidationResponseDTO(token);
        
        assertThat(dto.getJwtNodeToken()).isEqualTo(token);
    }

    @Test
    void noArgsConstructor_shouldCreateEmptyDto() {
        NodeValidationResponseDTO dto = new NodeValidationResponseDTO();
        
        assertThat(dto.getJwtNodeToken()).isNull();
    }

    @Test
    void setter_shouldUpdateJwtNodeToken() {
        NodeValidationResponseDTO dto = new NodeValidationResponseDTO();
        String token = "newToken123";
        
        dto.setJwtNodeToken(token);
        
        assertThat(dto.getJwtNodeToken()).isEqualTo(token);
    }

    @Test
    void setJwtNodeToken_withNull_shouldAllowNull() {
        NodeValidationResponseDTO dto = new NodeValidationResponseDTO("initialToken");
        
        dto.setJwtNodeToken(null);
        
        assertThat(dto.getJwtNodeToken()).isNull();
    }

    @Test
    void setJwtNodeToken_withEmptyString_shouldAllowEmpty() {
        NodeValidationResponseDTO dto = new NodeValidationResponseDTO();
        
        dto.setJwtNodeToken("");
        
        assertThat(dto.getJwtNodeToken()).isEmpty();
    }

    @Test
    void setJwtNodeToken_withLongToken_shouldHandleLongTokens() {
        NodeValidationResponseDTO dto = new NodeValidationResponseDTO();
        String longToken = "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJ0ZXN0VXNlciIsImlhdCI6MTYxNjI0MjQwMCwiZXhwIjoxNjE2MjQ2MDAwfQ.verylongbase64encodedsignaturehere";
        
        dto.setJwtNodeToken(longToken);
        
        assertThat(dto.getJwtNodeToken()).isEqualTo(longToken);
    }
}
