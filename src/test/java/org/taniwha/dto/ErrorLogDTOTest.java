package org.taniwha.dto;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.*;

class ErrorLogDTOTest {

    @Test
    void constructor_shouldCreateEmptyDto() {
        ErrorLogDTO dto = new ErrorLogDTO();
        
        assertThat(dto.getError()).isNull();
        assertThat(dto.getInfo()).isNull();
        assertThat(dto.getTimestamp()).isNull();
    }

    @Test
    void setters_shouldUpdateFields() {
        ErrorLogDTO dto = new ErrorLogDTO();
        
        dto.setError("NullPointerException");
        dto.setInfo("Error in data processing");
        dto.setTimestamp("2024-01-13T12:00:00");
        
        assertThat(dto.getError()).isEqualTo("NullPointerException");
        assertThat(dto.getInfo()).isEqualTo("Error in data processing");
        assertThat(dto.getTimestamp()).isEqualTo("2024-01-13T12:00:00");
    }

    @Test
    void setError_withNullPointerMessage_shouldStore() {
        ErrorLogDTO dto = new ErrorLogDTO();
        String errorMessage = "java.lang.NullPointerException: Cannot invoke method on null object";
        
        dto.setError(errorMessage);
        
        assertThat(dto.getError()).isEqualTo(errorMessage);
    }

    @Test
    void setInfo_withDetailedStackTrace_shouldHandleLongText() {
        ErrorLogDTO dto = new ErrorLogDTO();
        String longInfo = """
                Error occurred at line 123 in file Service.java
                Stack trace:
                  at com.example.Service.method(Service.java:123)
                  at com.example.Controller.handle(Controller.java:45)
                """;
        
        dto.setInfo(longInfo);
        
        assertThat(dto.getInfo()).contains("Stack trace");
        assertThat(dto.getInfo()).contains("Service.java:123");
    }

    @Test
    void setTimestamp_withIsoFormat_shouldStore() {
        ErrorLogDTO dto = new ErrorLogDTO();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        
        dto.setTimestamp(timestamp);
        
        assertThat(dto.getTimestamp()).isEqualTo(timestamp);
        assertThat(dto.getTimestamp()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");
    }

    @Test
    void allFieldsNull_shouldBeValid() {
        ErrorLogDTO dto = new ErrorLogDTO();
        dto.setError(null);
        dto.setInfo(null);
        dto.setTimestamp(null);
        
        assertThat(dto.getError()).isNull();
        assertThat(dto.getInfo()).isNull();
        assertThat(dto.getTimestamp()).isNull();
    }

    @Test
    void setError_withEmptyString_shouldAllowEmpty() {
        ErrorLogDTO dto = new ErrorLogDTO();
        
        dto.setError("");
        
        assertThat(dto.getError()).isEmpty();
    }

    @Test
    void multipleUpdates_shouldRetainLatestValues() {
        ErrorLogDTO dto = new ErrorLogDTO();
        
        dto.setError("First error");
        dto.setError("Second error");
        dto.setInfo("First info");
        dto.setInfo("Second info");
        
        assertThat(dto.getError()).isEqualTo("Second error");
        assertThat(dto.getInfo()).isEqualTo("Second info");
    }

    @Test
    void setTimestamp_withDifferentFormats_shouldStoreAsProvided() {
        ErrorLogDTO dto = new ErrorLogDTO();
        
        dto.setTimestamp("2024-01-13 12:00:00");
        assertThat(dto.getTimestamp()).isEqualTo("2024-01-13 12:00:00");
        
        dto.setTimestamp("1673611200000"); // Unix timestamp
        assertThat(dto.getTimestamp()).isEqualTo("1673611200000");
        
        dto.setTimestamp("2024-01-13T12:00:00.123Z");
        assertThat(dto.getTimestamp()).isEqualTo("2024-01-13T12:00:00.123Z");
    }
}
