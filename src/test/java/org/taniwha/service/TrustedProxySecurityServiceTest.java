package org.taniwha.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class TrustedProxySecurityServiceTest {

    @Test
    void verifyRequest_acceptsValidSignedRequestAndSignsResponse() {
        TrustedProxySecurityService service = new TrustedProxySecurityService("shared-secret", 300);
        byte[] body = "{\"kerberosToken\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/taniwha/node/validate");

        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = "nonce-1";
        String contentHash = sha256Base64(body);
        String signature = hmacBase64("shared-secret",
                String.join("\n", "REQ", "POST", "/taniwha/node/validate", contentHash, timestamp, nonce));

        request.addHeader(TrustedProxySecurityService.REQUEST_TIMESTAMP_HEADER, timestamp);
        request.addHeader(TrustedProxySecurityService.REQUEST_NONCE_HEADER, nonce);
        request.addHeader(TrustedProxySecurityService.REQUEST_CONTENT_HASH_HEADER, contentHash);
        request.addHeader(TrustedProxySecurityService.REQUEST_SIGNATURE_HEADER, signature);

        TrustedProxySecurityService.VerificationResult result = service.verifyRequest(request, body);

        assertTrue(result.valid());
        assertNotNull(result.context());

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.signResponse(response, result.context(), 200, "OK".getBytes(StandardCharsets.UTF_8));
        assertNotNull(response.getHeader(TrustedProxySecurityService.RESPONSE_SIGNATURE_HEADER));
        assertNotNull(response.getHeader(TrustedProxySecurityService.RESPONSE_CONTENT_HASH_HEADER));
        assertNotNull(response.getHeader(TrustedProxySecurityService.RESPONSE_TIMESTAMP_HEADER));
    }

    @Test
    void verifyRequest_rejectsReusedNonce() {
        TrustedProxySecurityService service = new TrustedProxySecurityService("shared-secret", 300);
        byte[] body = new byte[0];

        MockHttpServletRequest first = buildSignedRequest("nonce-1", body);
        MockHttpServletRequest second = buildSignedRequest("nonce-1", body);

        assertTrue(service.verifyRequest(first, body).valid());
        TrustedProxySecurityService.VerificationResult secondResult = service.verifyRequest(second, body);
        assertFalse(secondResult.valid());
        assertEquals("Trusted proxy nonce has already been used", secondResult.failureReason());
    }

    @Test
    void invalidSignaturesDoNotReserveNonces() {
        TrustedProxySecurityService service = new TrustedProxySecurityService("shared-secret", 300);
        byte[] body = new byte[0];
        MockHttpServletRequest invalid = buildSignedRequest("nonce-1", body);
        invalid.removeHeader(TrustedProxySecurityService.REQUEST_SIGNATURE_HEADER);
        invalid.addHeader(TrustedProxySecurityService.REQUEST_SIGNATURE_HEADER, "invalid");

        assertFalse(service.verifyRequest(invalid, body).valid());
        Map<?, ?> state = (Map<?, ?>) ReflectionTestUtils.getField(service, "seenNonces");
        assertTrue(state.isEmpty());
        assertTrue(service.verifyRequest(buildSignedRequest("nonce-1", body), body).valid());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullCacheFailsClosedWithoutEvictingLiveNonces() {
        TrustedProxySecurityService service = new TrustedProxySecurityService("shared-secret", 300);
        Map<String, Long> state = (Map<String, Long>) ReflectionTestUtils.getField(service, "seenNonces");
        long now = Instant.now().getEpochSecond();
        IntStream.range(0, 10_000).forEach(index -> state.put("existing-" + index, now));

        assertFalse(service.verifyRequest(buildSignedRequest("new-nonce", new byte[0]), new byte[0]).valid());
        assertEquals(10_000, state.size());
        state.replaceAll((nonce, timestamp) -> now - 600);
        assertTrue(service.verifyRequest(buildSignedRequest("new-nonce", new byte[0]), new byte[0]).valid());
        assertEquals(1, state.size());
    }

    @Test
    void concurrentReplayHasOnlyOneSuccessfulRequest() throws Exception {
        TrustedProxySecurityService service = new TrustedProxySecurityService("shared-secret", 300);
        var executor = Executors.newFixedThreadPool(8);
        try {
            var calls = IntStream.range(0, 32)
                    .<Callable<Boolean>>mapToObj(index -> () -> service.verifyRequest(
                            buildSignedRequest("concurrent-nonce", new byte[0]), new byte[0]).valid())
                    .toList();
            int accepted = 0;
            for (Future<Boolean> result : executor.invokeAll(calls)) {
                if (result.get()) accepted++;
            }
            assertEquals(1, accepted);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsMissingHeadersAndInvalidTimestampsWithoutRetainingState() {
        TrustedProxySecurityService service = new TrustedProxySecurityService("shared-secret", 300);
        assertFalse(service.verifyRequest(new MockHttpServletRequest(), new byte[0]).valid());
        for (String timestamp : new String[]{"not-a-time",
                Long.toString(Instant.now().getEpochSecond() - 600),
                Long.toString(Instant.now().getEpochSecond() + 600)}) {
            MockHttpServletRequest request = buildSignedRequest("nonce", new byte[0]);
            request.removeHeader(TrustedProxySecurityService.REQUEST_TIMESTAMP_HEADER);
            request.addHeader(TrustedProxySecurityService.REQUEST_TIMESTAMP_HEADER, timestamp);
            assertFalse(service.verifyRequest(request, new byte[0]).valid());
        }
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(service, "seenNonces")).isEmpty());
    }

    @Test
    void rejectsTamperedBodyWithoutReservingNonce() {
        TrustedProxySecurityService service = new TrustedProxySecurityService("shared-secret", 300);
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = buildSignedRequest("nonce", original);
        assertFalse(service.verifyRequest(request, "modified".getBytes(StandardCharsets.UTF_8)).valid());
        assertTrue(service.verifyRequest(request, original).valid());
    }

    @Test
    void signatureBindsQueryParametersToTheRequest() {
        TrustedProxySecurityService service = new TrustedProxySecurityService("shared-secret", 300);
        MockHttpServletRequest request = buildSignedRequest("nonce", new byte[0]);
        request.setQueryString("fileName=another.csv");
        assertFalse(service.verifyRequest(request, new byte[0]).valid());
        request.setQueryString(null);
        assertTrue(service.verifyRequest(request, new byte[0]).valid());
    }

    @Test
    void publicUnsignedPaths_areDetected() {
        TrustedProxySecurityService service = new TrustedProxySecurityService("shared-secret", 300);
        MockHttpServletRequest metadataRequest = new MockHttpServletRequest("GET", "/taniwha/node/metadata");
        MockHttpServletRequest datasetRequest = new MockHttpServletRequest("GET", "/taniwha/api/files/datasets");

        assertTrue(service.isPublicUnsignedPath(metadataRequest));
        assertFalse(service.isPublicUnsignedPath(datasetRequest));
    }

    private MockHttpServletRequest buildSignedRequest(String nonce, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/taniwha/node/validate");
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String contentHash = sha256Base64(body);
        String signature = hmacBase64("shared-secret",
                String.join("\n", "REQ", "POST", "/taniwha/node/validate", contentHash, timestamp, nonce));
        request.addHeader(TrustedProxySecurityService.REQUEST_TIMESTAMP_HEADER, timestamp);
        request.addHeader(TrustedProxySecurityService.REQUEST_NONCE_HEADER, nonce);
        request.addHeader(TrustedProxySecurityService.REQUEST_CONTENT_HASH_HEADER, contentHash);
        request.addHeader(TrustedProxySecurityService.REQUEST_SIGNATURE_HEADER, signature);
        return request;
    }

    private String sha256Base64(byte[] body) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(body));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String hmacBase64(String secret, String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
