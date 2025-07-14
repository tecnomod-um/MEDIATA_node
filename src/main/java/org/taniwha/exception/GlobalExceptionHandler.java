package org.taniwha.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.taniwha.dto.ErrorLogDTO;
import org.taniwha.service.ErrorLoggingService;

import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ErrorLoggingService errorLoggingService;

    public GlobalExceptionHandler(ErrorLoggingService errorLoggingService) {
        this.errorLoggingService = errorLoggingService;
    }

    @ExceptionHandler(com.fasterxml.jackson.databind.JsonMappingException.class)
    public ResponseEntity<String> handleJsonMappingException(com.fasterxml.jackson.databind.JsonMappingException e) {
        logger.error("JSON parsing error: ", e);
        logError(e, "Invalid JSON structure");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Invalid JSON structure: " + e.getOriginalMessage());
    }

    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<String> handleHttpClientErrorException(HttpClientErrorException e) {
        logger.error("Client error: ", e);
        logError(e, "Client error");
        return ResponseEntity.status(e.getStatusCode())
                .body("Client error: " + e.getMessage());
    }

    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<String> handleHttpServerErrorException(HttpServerErrorException e) {
        logger.error("Server error: ", e);
        logError(e, "Server error");
        return ResponseEntity.status(e.getStatusCode())
                .body("Server error: " + e.getMessage());
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<String> handleRestClientException(RestClientException e) {
        logger.error("Error during REST call: ", e);
        logError(e, "REST call error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error during external service call: " + e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<String> handleNoResourceFoundException(NoResourceFoundException e) {
        logger.error("Resource not found: ", e);
        logError(e, "Resource not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body("The requested resource could not be found: " + e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleGeneralException(Exception e) {
        logger.error("Unexpected error: ", e);
        logError(e, "Unexpected error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("An unexpected internal error occurred: " + e.getMessage());
    }

    private void logError(Exception e, String additionalInfo) {
        ErrorLogDTO errorLogDTO = new ErrorLogDTO();
        errorLogDTO.setError(e.getMessage());
        errorLogDTO.setInfo(additionalInfo);
        errorLogDTO.setTimestamp(LocalDateTime.now().toString());
        try {
            errorLoggingService.logError(errorLogDTO);
        } catch (Exception ex) {
            logger.error("Error occurred in ErrorLoggingService: ", ex);
        }
    }
}
