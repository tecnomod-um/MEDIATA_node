package org.taniwha.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RegisterResponseDTOTest {

    @Test
    void constructor_shouldCreateEmptyDto() {
        RegisterResponseDTO dto = new RegisterResponseDTO();
        
        assertThat(dto.getMessage()).isNull();
        assertThat(dto.getKeytab()).isNull();
        assertThat(dto.getNodeId()).isNull();
    }

    @Test
    void setters_shouldUpdateFields() {
        RegisterResponseDTO dto = new RegisterResponseDTO();
        
        dto.setMessage("Registration successful");
        dto.setKeytab("base64EncodedKeytabData");
        dto.setNodeId("node-123");
        
        assertThat(dto.getMessage()).isEqualTo("Registration successful");
        assertThat(dto.getKeytab()).isEqualTo("base64EncodedKeytabData");
        assertThat(dto.getNodeId()).isEqualTo("node-123");
    }

    @Test
    void setMessage_withNull_shouldAllowNull() {
        RegisterResponseDTO dto = new RegisterResponseDTO();
        dto.setMessage("test");
        
        dto.setMessage(null);
        
        assertThat(dto.getMessage()).isNull();
    }

    @Test
    void setKeytab_withNull_shouldAllowNull() {
        RegisterResponseDTO dto = new RegisterResponseDTO();
        dto.setKeytab("data");
        dto.setNodeId("node-123");
        
        dto.setKeytab(null);
        dto.setNodeId(null);
        
        assertThat(dto.getKeytab()).isNull();
        assertThat(dto.getNodeId()).isNull();
    }

    @Test
    void setMessage_withEmptyString_shouldAllowEmpty() {
        RegisterResponseDTO dto = new RegisterResponseDTO();
        
        dto.setMessage("");
        
        assertThat(dto.getMessage()).isEmpty();
    }

    @Test
    void setKeytab_withBase64Data_shouldHandleLargeData() {
        RegisterResponseDTO dto = new RegisterResponseDTO();
        String largeKeytab = "A".repeat(10000); // Large keytab data
        
        dto.setKeytab(largeKeytab);
        
        assertThat(dto.getKeytab()).hasSize(10000);
    }

    @Test
    void multipleUpdates_shouldRetainLatestValues() {
        RegisterResponseDTO dto = new RegisterResponseDTO();
        
        dto.setMessage("First message");
        dto.setMessage("Second message");
        dto.setKeytab("First keytab");
        dto.setKeytab("Second keytab");
        dto.setNodeId("First node");
        dto.setNodeId("Second node");
        
        assertThat(dto.getMessage()).isEqualTo("Second message");
        assertThat(dto.getKeytab()).isEqualTo("Second keytab");
        assertThat(dto.getNodeId()).isEqualTo("Second node");
    }
}
