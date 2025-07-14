package org.taniwha.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.taniwha.dto.ErrorLogDTO;

@Service
public class ErrorLoggingService {

    private static final Logger logger = LoggerFactory.getLogger(ErrorLoggingService.class);
    private final RestTemplate restTemplate;
    @Value("${host.url}" + "${host.service}")
    private String centralBackendUrl;

    public ErrorLoggingService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void logError(ErrorLogDTO errorLogDTO) {
        String url = centralBackendUrl + "/api/error";
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            String jsonErrorLog = objectMapper.writeValueAsString(errorLogDTO);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(jsonErrorLog, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful())
                logger.error("Failed to log error. HTTP Status: {}", response.getStatusCode());
        } catch (JsonProcessingException e) {
            logger.error("Error serializing ErrorLogDTO to JSON: {}", e.getMessage());
        } catch (HttpStatusCodeException e) {
            logger.error("Error logging failed  with status code {} and response body {}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.error("An unexpected error occurred while logging the error: {}", e.getMessage(), e);
        }
    }
}
