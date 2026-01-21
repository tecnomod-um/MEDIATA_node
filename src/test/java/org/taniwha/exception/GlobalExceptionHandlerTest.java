package org.taniwha.exception;

import com.fasterxml.jackson.databind.JsonMappingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.taniwha.dto.ErrorLogDTO;
import org.taniwha.service.ErrorLoggingService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;

    @Mock
    private ErrorLoggingService errorLoggingService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        exceptionHandler = new GlobalExceptionHandler(errorLoggingService);
    }

    @Test
    void handleJsonMappingException_shouldReturnBadRequest() {
        JsonMappingException exception = mock(JsonMappingException.class);
        when(exception.getOriginalMessage()).thenReturn("Invalid field");

        ResponseEntity<String> response = exceptionHandler.handleJsonMappingException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Invalid JSON structure");
        assertThat(response.getBody()).contains("Invalid field");
        verify(errorLoggingService).logError(any(ErrorLogDTO.class));
    }

    @Test
    void handleHttpClientErrorException_shouldReturnClientError() {
        HttpClientErrorException exception = new HttpClientErrorException(
                HttpStatus.BAD_REQUEST, 
                "Bad request"
        );

        ResponseEntity<String> response = exceptionHandler.handleHttpClientErrorException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Client error");
        verify(errorLoggingService).logError(any(ErrorLogDTO.class));
    }

    @Test
    void handleHttpServerErrorException_shouldReturnServerError() {
        HttpServerErrorException exception = new HttpServerErrorException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Server error"
        );

        ResponseEntity<String> response = exceptionHandler.handleHttpServerErrorException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("Server error");
        verify(errorLoggingService).logError(any(ErrorLogDTO.class));
    }

    @Test
    void handleRestClientException_shouldReturnInternalServerError() {
        RestClientException exception = new RestClientException("Connection failed");

        ResponseEntity<String> response = exceptionHandler.handleRestClientException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("Error during external service call");
        assertThat(response.getBody()).contains("Connection failed");
        verify(errorLoggingService).logError(any(ErrorLogDTO.class));
    }

    @Test
    void handleNoResourceFoundException_shouldReturnNotFound() {
        NoResourceFoundException exception = new NoResourceFoundException(null, "/test/path");

        ResponseEntity<String> response = exceptionHandler.handleNoResourceFoundException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("The requested resource could not be found");
        verify(errorLoggingService).logError(any(ErrorLogDTO.class));
    }

    @Test
    void handleGeneralException_shouldReturnInternalServerError() {
        Exception exception = new Exception("Unexpected error");

        ResponseEntity<String> response = exceptionHandler.handleGeneralException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("An unexpected internal error occurred");
        assertThat(response.getBody()).contains("Unexpected error");
        verify(errorLoggingService).logError(any(ErrorLogDTO.class));
    }

    @Test
    void handleException_whenErrorLoggingServiceFails_shouldNotThrow() {
        doThrow(new RuntimeException("Logging failed"))
                .when(errorLoggingService).logError(any(ErrorLogDTO.class));
        
        Exception exception = new Exception("Test error");

        assertThatCode(() -> exceptionHandler.handleGeneralException(exception))
                .doesNotThrowAnyException();
    }

    @Test
    void handleHttpClientErrorException_withUnauthorized_shouldReturnUnauthorized() {
        HttpClientErrorException exception = new HttpClientErrorException(
                HttpStatus.UNAUTHORIZED,
                "Unauthorized"
        );

        ResponseEntity<String> response = exceptionHandler.handleHttpClientErrorException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void handleHttpClientErrorException_withForbidden_shouldReturnForbidden() {
        HttpClientErrorException exception = new HttpClientErrorException(
                HttpStatus.FORBIDDEN,
                "Forbidden"
        );

        ResponseEntity<String> response = exceptionHandler.handleHttpClientErrorException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void handleHttpServerErrorException_withBadGateway_shouldReturnBadGateway() {
        HttpServerErrorException exception = new HttpServerErrorException(
                HttpStatus.BAD_GATEWAY,
                "Bad gateway"
        );

        ResponseEntity<String> response = exceptionHandler.handleHttpServerErrorException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
