package org.taniwha.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

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
