package org.taniwha.exception;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.taniwha.dto.ErrorLogDTO;
import org.taniwha.service.ErrorLoggingService;

import java.time.LocalDateTime;

// Log the exception details
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ErrorLoggingService errorLoggingService;

    public GlobalExceptionHandler(ErrorLoggingService errorLoggingService) {
        this.errorLoggingService = errorLoggingService;
    }

    @ExceptionHandler(Exception.class)
    public void handleException(Exception e) {
        ErrorLogDTO errorLogDTO = new ErrorLogDTO();
        errorLogDTO.setError(e.getMessage());
        errorLogDTO.setInfo("Additional info about the error");
        errorLogDTO.setTimestamp(LocalDateTime.now());
        errorLoggingService.logError(errorLogDTO);
    }
}
