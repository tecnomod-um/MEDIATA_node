package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.taniwha.dto.ErrorLogDTO;

@Service
public class ErrorLoggingService {

    private static final Logger logger = LoggerFactory.getLogger(ErrorLoggingService.class);
    private final RestTemplate restTemplate;
    @Value("${central.backend.url}")
    private String centralBackendUrl;

    public ErrorLoggingService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void logError(ErrorLogDTO errorLogDTO) {
        String url = centralBackendUrl + "/api/error";
        ResponseEntity<String> response = restTemplate.postForEntity(url, errorLogDTO, String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            // Handle the error appropriately
            logger.error("Failed to log error: {}", response.getStatusCode());
        }
    }
}
