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

import static org.mockito.Mockito.*;

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

        service = new NodeSyncService(
                restHolder, retryTemplate, jwtUtil, keyPairUtil, principalService
        );
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "centralBackendUrl", "http://central"
        );
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "nodeIp", "1.2.3.4"
        );
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "nodeName", "node1"
        );
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "nodeDescription", "desc"
        );
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "jwtUsername", "user"
        );
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "nodeColor", ""
        );
    }

    @SuppressWarnings("unchecked")
    private <T> void stubRetryTemplate() {
        when(retryTemplate.execute(any(RetryCallback.class)))
                .thenAnswer(inv -> {
                    RetryCallback<T, RestClientException> cb = inv.getArgument(0);
                    RetryContext ctx = mock(RetryContext.class);
                    when(ctx.getRetryCount()).thenReturn(0);
                    return cb.doWithRetry(ctx);
                });
    }

    @Test
    void registerWithCentralBackend_successful_setsKeytab() {
        when(jwtUtil.generateToken("user")).thenReturn("JWT");
        RegisterResponseDTO resp = new RegisterResponseDTO();
        resp.setKeytab("KEY");
        when(restTemplate.postForObject(
                eq("http://central/nodes/register"),
                any(HttpEntity.class),
                eq(RegisterResponseDTO.class)))
                .thenReturn(resp);
        stubRetryTemplate();

        service.registerWithCentralBackend();

        verify(principalService).setKeytab("KEY");
    }

    @Test
    void registerWithCentralBackend_sslHandshake_refreshesAndSucceeds() {
        when(jwtUtil.generateToken("user")).thenReturn("JWT");
        RegisterResponseDTO resp = new RegisterResponseDTO();
        resp.setKeytab("KEY2");
        when(restTemplate.postForObject(anyString(), any(), eq(RegisterResponseDTO.class)))
                .thenThrow(new ResourceAccessException("io", new SSLHandshakeException("ssl")))
                .thenReturn(resp);

        stubRetryTemplate();

        service.registerWithCentralBackend();

        verify(restHolder).refresh();
        verify(principalService).setKeytab("KEY2");
    }

    @Test
    void registerWithCentralBackend_conflict_doesNotSetKeytab() {
        when(jwtUtil.generateToken("user")).thenReturn("JWT");
        when(restTemplate.postForObject(anyString(), any(), eq(RegisterResponseDTO.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));

        stubRetryTemplate();
        service.registerWithCentralBackend();
        verify(principalService, never()).setKeytab(any());
    }

    @Test
    void sendHeartbeat_success_postsOnce() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectId", "OID");
        when(jwtUtil.generateToken("user")).thenReturn("JWT2");
        stubRetryTemplate();
        service.sendHeartbeat();
        verify(restTemplate)
                .postForEntity(
                        eq("http://central/nodes/heartbeat"),
                        any(HttpEntity.class),
                        eq(String.class)
                );
    }

    @Test
    void sendHeartbeat_notFound_triggersReRegister() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectId", "OID");
        when(jwtUtil.generateToken("user")).thenReturn("JWT3");
        when(retryTemplate.execute(any()))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        NodeSyncService spy = spy(service);
        doNothing().when(spy).registerWithCentralBackend();
        spy.sendHeartbeat();
        verify(spy).registerWithCentralBackend();
    }

    @Test
    void logErrorToCentralBackend_success_postsOnce() {
        when(jwtUtil.generateToken("user")).thenReturn("JWT4");
        stubRetryTemplate();
        service.logErrorToCentralBackend("err", "info");
        verify(restTemplate)
                .postForEntity(
                        eq("http://central/api/error"),
                        any(HttpEntity.class),
                        eq(String.class)
                );
    }

    @Test
    void logErrorToCentralBackend_sslHandshake_refreshesAndPostsTwice() {
        when(jwtUtil.generateToken("user")).thenReturn("JWT5");
        ResponseEntity<String> ok = ResponseEntity.ok("ok");

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("io", new SSLHandshakeException("ssl")))
                .thenReturn(ok);
        stubRetryTemplate();
        service.logErrorToCentralBackend("err2", "info2");

        verify(restHolder).refresh();
        verify(restTemplate, times(2))
                .postForEntity(anyString(), any(), eq(String.class));
    }

    // -------------------------------------------------------------------------
    // sendHeartbeat – SSL handshake + generic RestClientException
    // -------------------------------------------------------------------------

    @Test
    void sendHeartbeat_sslHandshake_refreshesAndRetries() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectId", "OID");
        when(jwtUtil.generateToken("user")).thenReturn("JWT-SSL");
        ResponseEntity<String> ok = ResponseEntity.ok("ok");

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("io", new SSLHandshakeException("ssl")))
                .thenReturn(ok);
        stubRetryTemplate();

        service.sendHeartbeat();

        verify(restHolder).refresh();
        verify(restTemplate, times(2))
                .postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void sendHeartbeat_restClientException_logsAndDoesNotThrow() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectId", "OID");
        when(jwtUtil.generateToken("user")).thenReturn("JWT-RCE");
        when(retryTemplate.execute(any()))
                .thenThrow(new org.springframework.web.client.RestClientException("network down"));

        // should not throw
        service.sendHeartbeat();
    }

    // -------------------------------------------------------------------------
    // handleHeartbeatException – via sendHeartbeat (HttpClientErrorException)
    // -------------------------------------------------------------------------

    @Test
    void sendHeartbeat_http400_logsErrorAndSendsErrorLog() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectId", "OID");
        when(jwtUtil.generateToken("user")).thenReturn("JWT-400");

        stubRetryTemplate_heartbeatThrowsThenErrorLogSucceeds(
                HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST, "Bad Request",
                        org.springframework.http.HttpHeaders.EMPTY,
                        "bad body".getBytes(), java.nio.charset.StandardCharsets.UTF_8));

        service.sendHeartbeat();

        verify(restTemplate).postForEntity(
                eq("http://central/api/error"), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void sendHeartbeat_http401_logsErrorAndSendsErrorLog() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectId", "OID");
        when(jwtUtil.generateToken("user")).thenReturn("JWT-401");

        stubRetryTemplate_heartbeatThrowsThenErrorLogSucceeds(
                HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized",
                        org.springframework.http.HttpHeaders.EMPTY,
                        "".getBytes(), java.nio.charset.StandardCharsets.UTF_8));

        service.sendHeartbeat();

        verify(restTemplate).postForEntity(
                eq("http://central/api/error"), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void sendHeartbeat_http500_logsErrorAndSendsErrorLog() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectId", "OID");
        when(jwtUtil.generateToken("user")).thenReturn("JWT-500");

        stubRetryTemplate_heartbeatThrowsThenErrorLogSucceeds(
                HttpClientErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Server Error",
                        org.springframework.http.HttpHeaders.EMPTY,
                        "".getBytes(), java.nio.charset.StandardCharsets.UTF_8));

        service.sendHeartbeat();

        verify(restTemplate).postForEntity(
                eq("http://central/api/error"), any(HttpEntity.class), eq(String.class));
    }

    // -------------------------------------------------------------------------
    // registerWithCentralBackend – non-conflict HttpClientErrorException
    // -------------------------------------------------------------------------

    @Test
    void registerWithCentralBackend_nonConflictHttpError_doesNotSetKeytab() {
        when(jwtUtil.generateToken("user")).thenReturn("JWT-NF");
        when(restTemplate.postForObject(anyString(), any(), eq(RegisterResponseDTO.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        stubRetryTemplate();

        service.registerWithCentralBackend();

        verify(principalService, never()).setKeytab(any());
    }

    // -------------------------------------------------------------------------
    // logErrorToCentralBackend – non-SSL ResourceAccessException
    // -------------------------------------------------------------------------

    @Test
    void logErrorToCentralBackend_nonSslResourceAccess_rethrowsViaRetry() {
        when(jwtUtil.generateToken("user")).thenReturn("JWT-RE");
        when(retryTemplate.execute(any()))
                .thenThrow(new ResourceAccessException("network timeout"));

        // should not throw – retry exhausted and RestClientException is caught
        service.logErrorToCentralBackend("e", "i");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Stubs the retry template so the first call (heartbeat) throws the given
     * exception, and the second call (from logErrorToCentralBackend inside
     * handleHeartbeatException) executes the callback normally.
     */
    @SuppressWarnings("unchecked")
    private void stubRetryTemplate_heartbeatThrowsThenErrorLogSucceeds(
            HttpClientErrorException heartbeatException) {
        when(retryTemplate.execute(any(RetryCallback.class)))
                .thenThrow(heartbeatException)
                .thenAnswer(inv -> {
                    RetryCallback<Object, RestClientException> cb = inv.getArgument(0);
                    RetryContext ctx = mock(RetryContext.class);
                    when(ctx.getRetryCount()).thenReturn(0);
                    return cb.doWithRetry(ctx);
                });
    }
}
