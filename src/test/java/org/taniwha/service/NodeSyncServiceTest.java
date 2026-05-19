package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.taniwha.config.RestTemplateHolder;
import org.taniwha.dto.RegisterResponseDTO;
import org.taniwha.util.JwtTokenUtil;
import org.taniwha.util.KeyPairUtil;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class NodeSyncServiceTest {

    private NodeSyncService service;
    private RestTemplateHolder restHolder;
    private RetryTemplate retryTemplate;
    private JwtTokenUtil jwtUtil;
    private PrincipalService principalService;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        restHolder = mock(RestTemplateHolder.class);
        retryTemplate = mock(RetryTemplate.class);
        jwtUtil = mock(JwtTokenUtil.class);
        KeyPairUtil keyPairUtil = mock(KeyPairUtil.class);
        principalService = mock(PrincipalService.class);
        restTemplate = mock(RestTemplate.class);

        when(keyPairUtil.getPublicKeyBase64()).thenReturn("PUBKEY");
        when(restHolder.get()).thenReturn(restTemplate);

        service = new NodeSyncService(restHolder, retryTemplate, jwtUtil, keyPairUtil, principalService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "centralBackendUrl", "http://central");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "nodeIp", "1.2.3.4");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "nodeName", "node1");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "nodeDescription", "desc");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "jwtUsername", "user");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "nodeColor", "");
    }

    @SuppressWarnings("unchecked")
    private <T> void stubRetryTemplate(int retryCount) {
        when(retryTemplate.execute(any(RetryCallback.class))).thenAnswer(invocation -> {
            RetryCallback<T, RestClientException> callback = invocation.getArgument(0);
            RetryContext context = mock(RetryContext.class);
            when(context.getRetryCount()).thenReturn(retryCount);
            return callback.doWithRetry(context);
        });
    }

    private void stubToken() {
        when(jwtUtil.generateToken("user")).thenReturn("JWT");
    }

    @Test
    void registerWithCentralBackend_successful_setsKeytab() {
        stubToken();
        RegisterResponseDTO response = new RegisterResponseDTO();
        response.setKeytab("KEY");
        when(restTemplate.postForObject(eq("http://central/nodes/register"), any(HttpEntity.class), eq(RegisterResponseDTO.class)))
                .thenReturn(response);
        stubRetryTemplate(0);

        service.registerWithCentralBackend();

        verify(principalService).setKeytab("KEY");
    }

    @Test
    void registerWithCentralBackend_withRetry_stillRegisters() {
        stubToken();
        RegisterResponseDTO response = new RegisterResponseDTO();
        response.setKeytab("RETRY-KEY");
        when(restTemplate.postForObject(anyString(), any(), eq(RegisterResponseDTO.class))).thenReturn(response);
        stubRetryTemplate(1);

        service.registerWithCentralBackend();

        verify(principalService).setKeytab("RETRY-KEY");
    }

    @Test
    void registerWithCentralBackend_sslHandshake_refreshesAndSucceeds() {
        stubToken();
        RegisterResponseDTO response = new RegisterResponseDTO();
        response.setKeytab("KEY2");
        when(restTemplate.postForObject(anyString(), any(), eq(RegisterResponseDTO.class)))
                .thenThrow(new ResourceAccessException("io", new SSLHandshakeException("ssl")))
                .thenReturn(response);
        stubRetryTemplate(0);

        service.registerWithCentralBackend();

        verify(restHolder).refresh();
        verify(principalService).setKeytab("KEY2");
    }

    @Test
    void registerWithCentralBackend_conflict_doesNotSetKeytab() {
        stubToken();
        when(restTemplate.postForObject(anyString(), any(), eq(RegisterResponseDTO.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));
        stubRetryTemplate(0);

        service.registerWithCentralBackend();

        verify(principalService, never()).setKeytab(any());
    }

    @Test
    void registerWithCentralBackend_badRequest_doesNotSetKeytab() {
        stubToken();
        when(restTemplate.postForObject(anyString(), any(), eq(RegisterResponseDTO.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));
        stubRetryTemplate(0);

        service.registerWithCentralBackend();

        verify(principalService, never()).setKeytab(any());
    }

    @Test
    void registerWithCentralBackend_nonSslResourceAccess_throwsException() {
        stubToken();
        when(restTemplate.postForObject(anyString(), any(), eq(RegisterResponseDTO.class)))
                .thenThrow(new ResourceAccessException("io"));
        stubRetryTemplate(0);

        assertThrows(ResourceAccessException.class, service::registerWithCentralBackend);
    }

    @Test
    void sendHeartbeat_success_postsOnce() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectId", "OID");
        stubToken();
        stubRetryTemplate(0);

        service.sendHeartbeat();

        verify(restTemplate).postForEntity(eq("http://central/nodes/heartbeat"), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void sendHeartbeat_withRetry_postsHeartbeat() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectId", "OID");
        stubToken();
        stubRetryTemplate(2);

        service.sendHeartbeat();

        verify(restTemplate).postForEntity(eq("http://central/nodes/heartbeat"), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void sendHeartbeat_notFound_triggersReRegister() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectId", "OID");
        stubToken();
        when(retryTemplate.execute(any())).thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        NodeSyncService spyService = spy(service);
        doNothing().when(spyService).registerWithCentralBackend();
        spyService.sendHeartbeat();

        verify(spyService).registerWithCentralBackend();
    }

    @Test
    void sendHeartbeat_badRequest_logsErrorToCentralBackend() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectId", "OID");
        stubToken();
        when(retryTemplate.execute(any())).thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        NodeSyncService spyService = spy(service);
        doNothing().when(spyService).logErrorToCentralBackend(anyString(), anyString());
        spyService.sendHeartbeat();

        verify(spyService).logErrorToCentralBackend(anyString(), anyString());
        verify(spyService, never()).registerWithCentralBackend();
    }

    @Test
    void sendHeartbeat_unauthorized_logsErrorToCentralBackend() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectId", "OID");
        stubToken();
        when(retryTemplate.execute(any())).thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        NodeSyncService spyService = spy(service);
        doNothing().when(spyService).logErrorToCentralBackend(anyString(), anyString());
        spyService.sendHeartbeat();

        verify(spyService).logErrorToCentralBackend(anyString(), anyString());
        verify(spyService, never()).registerWithCentralBackend();
    }

    @Test
    void sendHeartbeat_internalServerError_logsErrorToCentralBackend() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectId", "OID");
        stubToken();
        when(retryTemplate.execute(any())).thenThrow(new HttpClientErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        NodeSyncService spyService = spy(service);
        doNothing().when(spyService).logErrorToCentralBackend(anyString(), anyString());
        spyService.sendHeartbeat();

        verify(spyService).logErrorToCentralBackend(anyString(), anyString());
        verify(spyService, never()).registerWithCentralBackend();
    }

    @Test
    void sendHeartbeat_nonSslResourceAccess_doesNotReRegister() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectId", "OID");
        stubToken();
        when(retryTemplate.execute(any())).thenThrow(new ResourceAccessException("io"));

        NodeSyncService spyService = spy(service);
        spyService.sendHeartbeat();

        verify(spyService, never()).registerWithCentralBackend();
    }

    @Test
    void deregisterFromCentralBackend_success_postsNodeId() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectId", "OID");
        stubToken();
        stubRetryTemplate(0);

        service.deregisterFromCentralBackend();

        verify(restTemplate).postForEntity(eq("http://central/nodes/deregister"), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void deregisterFromCentralBackend_notFound_isIgnored() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectId", "OID");
        stubToken();
        when(retryTemplate.execute(any())).thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        service.deregisterFromCentralBackend();

        verify(restTemplate, never()).postForEntity(eq("http://central/api/error"), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void deregisterFromCentralBackend_withoutNodeId_doesNothing() {
        stubToken();

        service.deregisterFromCentralBackend();

        verifyNoMoreInteractions(restTemplate);
    }

    @Test
    void logErrorToCentralBackend_success_postsOnce() {
        stubToken();
        stubRetryTemplate(0);

        service.logErrorToCentralBackend("err", "info");

        verify(restTemplate).postForEntity(eq("http://central/api/error"), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void logErrorToCentralBackend_withRetry_postsError() {
        stubToken();
        stubRetryTemplate(1);

        service.logErrorToCentralBackend("err", "info");

        verify(restTemplate).postForEntity(eq("http://central/api/error"), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void logErrorToCentralBackend_sslHandshake_refreshesAndPostsTwice() {
        stubToken();
        ResponseEntity<String> response = ResponseEntity.ok("ok");

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("io", new SSLHandshakeException("ssl")))
                .thenReturn(response);
        stubRetryTemplate(0);

        service.logErrorToCentralBackend("err2", "info2");

        verify(restHolder).refresh();
        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void logErrorToCentralBackend_nonSslResourceAccess_doesNotRefresh() {
        stubToken();
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class))).thenThrow(new ResourceAccessException("io"));
        stubRetryTemplate(0);

        service.logErrorToCentralBackend("err", "info");

        verify(restHolder, never()).refresh();
    }
}
