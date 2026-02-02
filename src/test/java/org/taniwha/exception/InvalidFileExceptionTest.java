package org.taniwha.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class InvalidFileExceptionTest {

    @Test
    void constructor_withMessage_shouldSetMessage() {
        String message = "File extension not allowed";
        
        InvalidFileException exception = new InvalidFileException(message);
        
        assertThat(exception.getMessage()).isEqualTo(message);
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void constructor_withMessageAndCause_shouldSetBoth() {
        String message = "File validation failed";
        Throwable cause = new IllegalArgumentException("Invalid argument");
        
        InvalidFileException exception = new InvalidFileException(message, cause);
        
        assertThat(exception.getMessage()).isEqualTo(message);
        assertThat(exception.getCause()).isEqualTo(cause);
        assertThat(exception.getCause().getMessage()).isEqualTo("Invalid argument");
    }

    @Test
    void exception_shouldBeRuntimeException() {
        InvalidFileException exception = new InvalidFileException("test");
        
        assertThat(exception).isInstanceOf(RuntimeException.class);
    }

    @Test
    void throwException_shouldBeThrowable() {
        assertThatThrownBy(() -> {
            throw new InvalidFileException("File is not valid");
        })
        .isInstanceOf(InvalidFileException.class)
        .hasMessage("File is not valid");
    }

    @Test
    void constructor_withNullMessage_shouldAllowNull() {
        InvalidFileException exception = new InvalidFileException(null);
        
        assertThat(exception.getMessage()).isNull();
    }

    @Test
    void constructor_withNullCause_shouldAllowNullCause() {
        InvalidFileException exception = new InvalidFileException("Message", null);
        
        assertThat(exception.getMessage()).isEqualTo("Message");
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void exception_shouldBeCatchableAsRuntimeException() {
        try {
            throw new InvalidFileException("Test exception");
        } catch (RuntimeException e) {
            assertThat(e).isInstanceOf(InvalidFileException.class);
            assertThat(e.getMessage()).isEqualTo("Test exception");
        }
    }

    @Test
    void exceptionWithCause_shouldPreserveStackTrace() {
        Exception cause = new Exception("Root cause");
        InvalidFileException exception = new InvalidFileException("Wrapper", cause);
        
        assertThat(exception.getCause()).isNotNull();
        assertThat(exception.getCause().getStackTrace()).isNotEmpty();
    }

    @Test
    void constructor_withDetailedMessage_shouldStoreFullMessage() {
        String detailedMessage = "File 'data.txt' has invalid extension. Allowed: csv, tsv, xlsx. Got: txt";
        
        InvalidFileException exception = new InvalidFileException(detailedMessage);
        
        assertThat(exception.getMessage()).isEqualTo(detailedMessage);
        assertThat(exception.getMessage()).contains("data.txt");
        assertThat(exception.getMessage()).contains("csv, tsv, xlsx");
    }
}
