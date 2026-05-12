package org.taniwha.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TrustedProxySecurityService {

    public static final String REQUEST_TIMESTAMP_HEADER = "X-Taniwha-Timestamp";
    public static final String REQUEST_NONCE_HEADER = "X-Taniwha-Nonce";
    public static final String REQUEST_CONTENT_HASH_HEADER = "X-Taniwha-Content-SHA256";
    public static final String REQUEST_SIGNATURE_HEADER = "X-Taniwha-Signature";

    public static final String RESPONSE_TIMESTAMP_HEADER = "X-Taniwha-Response-Timestamp";
    public static final String RESPONSE_CONTENT_HASH_HEADER = "X-Taniwha-Response-Content-SHA256";
    public static final String RESPONSE_SIGNATURE_HEADER = "X-Taniwha-Response-Signature";

    public static final String SIGNED_PROXY_CONTEXT_ATTRIBUTE = TrustedProxySecurityService.class.getName() + ".SIGNED_PROXY_CONTEXT";

    private final String sharedSecret;
    private final long maxClockSkewSeconds;
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public TrustedProxySecurityService(
            @Value("${trusted.proxy.shared-secret:}") String sharedSecret,
            @Value("${trusted.proxy.max-clock-skew-seconds:300}") long maxClockSkewSeconds
    ) {
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret.trim();
        this.maxClockSkewSeconds = maxClockSkewSeconds;
    }

    public boolean isEnabled() {
        return !sharedSecret.isBlank();
    }

    public boolean isPublicUnsignedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.endsWith("/node/health")
                || path.endsWith("/node/metadata")
                || path.contains("/fdp");
    }

    public boolean hasSignatureHeaders(HttpServletRequest request) {
        return !isBlank(request.getHeader(REQUEST_TIMESTAMP_HEADER))
                || !isBlank(request.getHeader(REQUEST_NONCE_HEADER))
                || !isBlank(request.getHeader(REQUEST_CONTENT_HASH_HEADER))
                || !isBlank(request.getHeader(REQUEST_SIGNATURE_HEADER));
    }

    public VerificationResult verifyRequest(HttpServletRequest request, byte[] body) {
        String timestamp = request.getHeader(REQUEST_TIMESTAMP_HEADER);
        String nonce = request.getHeader(REQUEST_NONCE_HEADER);
        String contentHash = request.getHeader(REQUEST_CONTENT_HASH_HEADER);
        String signature = request.getHeader(REQUEST_SIGNATURE_HEADER);

        if (isBlank(timestamp) || isBlank(nonce) || isBlank(contentHash) || isBlank(signature)) {
            return VerificationResult.failure("Missing trusted proxy signature headers");
        }

        long now = Instant.now().getEpochSecond();
        long requestTimestamp;
        try {
            requestTimestamp = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return VerificationResult.failure("Invalid trusted proxy timestamp");
        }

        if (Math.abs(now - requestTimestamp) > maxClockSkewSeconds) {
            return VerificationResult.failure("Trusted proxy timestamp is outside the allowed clock skew");
        }

        cleanupExpiredNonces(now);
        Long previousTimestamp = seenNonces.putIfAbsent(nonce, requestTimestamp);
        if (previousTimestamp != null) {
            return VerificationResult.failure("Trusted proxy nonce has already been used");
        }

        String expectedContentHash = sha256Base64(body);
        if (!expectedContentHash.equals(contentHash)) {
            return VerificationResult.failure("Trusted proxy content hash mismatch");
        }

        String pathAndQuery = canonicalPathAndQuery(request);
        String expectedSignature = hmacBase64(requestCanonical(
                request.getMethod(),
                pathAndQuery,
                contentHash,
                timestamp,
                nonce
        ));
        if (!MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                expectedSignature.getBytes(StandardCharsets.UTF_8))) {
            return VerificationResult.failure("Trusted proxy signature mismatch");
        }

        return VerificationResult.success(new SignedRequestContext(request.getMethod(), pathAndQuery, nonce));
    }

    public void signResponse(HttpServletResponse response, SignedRequestContext requestContext, int statusCode, byte[] body) {
        if (!isEnabled() || requestContext == null) {
            return;
        }

        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String contentHash = sha256Base64(body);
        String signature = hmacBase64(responseCanonical(
                statusCode,
                requestContext.method(),
                requestContext.pathAndQuery(),
                requestContext.requestNonce(),
                timestamp,
                contentHash
        ));

        response.setHeader(RESPONSE_TIMESTAMP_HEADER, timestamp);
        response.setHeader(RESPONSE_CONTENT_HASH_HEADER, contentHash);
        response.setHeader(RESPONSE_SIGNATURE_HEADER, signature);
    }

    private void cleanupExpiredNonces(long now) {
        long threshold = now - Math.max(maxClockSkewSeconds, 1);
        seenNonces.entrySet().removeIf(entry -> entry.getValue() < threshold);
    }

    private String canonicalPathAndQuery(HttpServletRequest request) {
        String path = request.getRequestURI();
        String query = request.getQueryString();
        if (query == null || query.isBlank()) {
            return path;
        }
        return path + "?" + query;
    }

    private String sha256Base64(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] normalizedBody = body == null ? new byte[0] : body;
            return Base64.getEncoder().encodeToString(digest.digest(normalizedBody));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String requestCanonical(String method, String pathAndQuery, String contentHash, String timestamp, String nonce) {
        return String.join("\n", "REQ", method, pathAndQuery, contentHash, timestamp, nonce);
    }

    private String responseCanonical(int statusCode, String method, String pathAndQuery, String requestNonce, String timestamp, String contentHash) {
        return String.join("\n", "RESP", Integer.toString(statusCode), method, pathAndQuery, requestNonce, timestamp, contentHash);
    }

    private String hmacBase64(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate trusted proxy signature", e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record SignedRequestContext(String method, String pathAndQuery, String requestNonce) {
    }

    public record VerificationResult(boolean valid, String failureReason, SignedRequestContext context) {
        public static VerificationResult success(SignedRequestContext context) {
            return new VerificationResult(true, null, context);
        }

        public static VerificationResult failure(String reason) {
            return new VerificationResult(false, reason, null);
        }
    }
}
