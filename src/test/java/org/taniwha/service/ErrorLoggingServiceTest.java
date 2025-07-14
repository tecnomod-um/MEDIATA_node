package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.taniwha.dto.ErrorLogDTO;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ErrorLoggingServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private ErrorLoggingService service;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new ErrorLoggingService(restTemplate);
        Field urlField = ErrorLoggingService.class.getDeclaredField("centralBackendUrl");
        urlField.setAccessible(true);
        urlField.set(service, "http://central-host");
    }

    @Captor
    private ArgumentCaptor<HttpEntity<String>> entityCaptor;

    @BeforeEach
    void initCaptor() {
        MockitoAnnotations.openMocks(this);
    }

    private ErrorLogDTO sampleDto() {
        ErrorLogDTO dto = new ErrorLogDTO();
        dto.setError("Something failed");
        dto.setInfo("More info");
        dto.setTimestamp("2025-07-11T19:00:00Z");
        return dto;
    }

    @Test
    void logError_when2xxResponse_doesNotThrow() {
        when(restTemplate.postForEntity(
                eq("http://central-host/api/error"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("", HttpStatus.CREATED));

        service.logError(sampleDto());
        verify(restTemplate).postForEntity(
                eq("http://central-host/api/error"),
                any(HttpEntity.class),
                eq(String.class));
    }

    @Test
    void logError_whenNon2xxResponse_logsErrorButDoesNotThrow() {
        when(restTemplate.postForEntity(
                eq("http://central-host/api/error"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("", HttpStatus.INTERNAL_SERVER_ERROR));

        service.logError(sampleDto());

        verify(restTemplate).postForEntity(
                eq("http://central-host/api/error"),
                any(HttpEntity.class),
                eq(String.class));
    }

    @Test
    void logError_whenHttpStatusCodeException_isCaught() {
        HttpStatusCodeException ex = new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad request");
        when(restTemplate.postForEntity(
                eq("http://central-host/api/error"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(ex);

        service.logError(sampleDto());

        verify(restTemplate).postForEntity(
                eq("http://central-host/api/error"),
                any(HttpEntity.class),
                eq(String.class));
    }

    @Test
    void logError_whenRuntimeException_isCaught() {
        when(restTemplate.postForEntity(
                eq("http://central-host/api/error"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new RuntimeException("Network down"));

        service.logError(sampleDto());

        verify(restTemplate).postForEntity(
                eq("http://central-host/api/error"),
                any(HttpEntity.class),
                eq(String.class));
    }

    @Test
    void logError_whenSerializationFails_doesNotCallRestTemplate() {
        ErrorLogDTO bad = new ErrorLogDTO() {
            @Override
            public String getInfo() {
                throw new RuntimeException("oops");
            }
        };
        service.logError(bad);
        verifyNoInteractions(restTemplate);
    }

    @Test
    void logError_buildsCorrectHttpEntity() {
        when(restTemplate.postForEntity(
                eq("http://central-host/api/error"),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("", HttpStatus.OK));

        ErrorLogDTO dto = sampleDto();
        service.logError(dto);

        verify(restTemplate).postForEntity(
                eq("http://central-host/api/error"),
                entityCaptor.capture(),
                eq(String.class));

        HttpEntity<String> ent = entityCaptor.getValue();
        assertThat(ent.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        String body = ent.getBody();
        assertThat(body)
                .contains("\"error\":\"Something failed\"")
                .contains("\"info\":\"More info\"")
                .contains("\"timestamp\":\"2025-07-11T19:00:00Z\"");
    }
}
