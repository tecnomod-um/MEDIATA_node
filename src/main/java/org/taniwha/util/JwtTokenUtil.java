package org.taniwha.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtTokenUtil {

    private static final String NODE_SYNC_ISSUER = "mediata-node";
    private static final String NODE_SYNC_AUDIENCE = "mediata-orchestrator";
    private static final String NODE_ACCESS_ISSUER = "mediata-node";
    private static final String NODE_ACCESS_AUDIENCE = "mediata-node-access";
    private static final String CENTRAL_USER_ISSUER = "mediata-orchestrator";
    private static final String CENTRAL_USER_AUDIENCE = "mediata-central";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    @Value("${node.sync.jwt.expiration:120}")
    private long nodeSyncExpiration;

    @Value("${jwt.node-id:${node.ip:http://localhost}}")
    private String nodeId;

    @PostConstruct
    void validateConfiguration() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must be configured with at least 32 bytes");
        }
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(String subject) {
        return generateToken(subject, Map.of());
    }

    public String generateNodeAccessToken(String subject) {
        return Jwts.builder()
                .claims(Map.of(
                        "token_use", "node_access",
                        "node_id", nodeId,
                        "aud", NODE_ACCESS_AUDIENCE
                ))
                .issuer(NODE_ACCESS_ISSUER)
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration * 1000))
                .signWith(getSigningKey())
                .compact();
    }

    public String generateNodeSyncToken(String nodeId, String endpoint, PrivateKey privateKey) {
        return Jwts.builder()
                .claims(Map.of(
                        "token_use", "node_sync",
                        "node_id", nodeId,
                        "sync_endpoint", endpoint,
                        "aud", NODE_SYNC_AUDIENCE
                ))
                .issuer(NODE_SYNC_ISSUER)
                .subject(nodeId)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + nodeSyncExpiration * 1000))
                .signWith(privateKey)
                .compact();
    }

    public String generateToken(String subject, Map<String, Object> claims) {
        Map<String, Object> safeClaims = claims == null ? Map.of() : new HashMap<>(claims);
        return doGenerateToken(safeClaims, subject);
    }

    public String getClaimAsString(String token, String claimName) {
        Object claimValue = getAllClaimsFromToken(token).get(claimName);
        return claimValue == null ? null : String.valueOf(claimValue);
    }

    public boolean hasClaimValue(String token, String claimName, String expectedValue) {
        String claimValue = getClaimAsString(token, claimName);
        return expectedValue != null && expectedValue.equals(claimValue);
    }

    public boolean isNodeAccessToken(String token) {
        try {
            Claims claims = getAllClaimsFromToken(token);
            return "node_access".equals(claims.get("token_use", String.class))
                    && NODE_ACCESS_ISSUER.equals(claims.getIssuer())
                    && claims.getAudience().contains(NODE_ACCESS_AUDIENCE)
                    && nodeId.equals(claims.get("node_id", String.class))
                    && claims.getExpiration() != null
                    && !claims.getExpiration().before(new Date());
        } catch (RuntimeException e) {
            return false;
        }
    }

    public boolean validateToken(String token) {
        return !isTokenExpired(token);
    }

    public boolean isCentralUserToken(String token) {
        try {
            Claims claims = getAllClaimsFromToken(token);
            return "central_user".equals(claims.get("token_use", String.class))
                    && CENTRAL_USER_ISSUER.equals(claims.getIssuer())
                    && claims.getAudience() != null
                    && claims.getAudience().contains(CENTRAL_USER_AUDIENCE)
                    && claims.getSubject() != null
                    && claims.getExpiration() != null
                    && !claims.getExpiration().before(new Date());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String doGenerateToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiration * 1000))
                .signWith(getSigningKey())
                .compact();
    }

    public String getUsernameFromToken(String token) {
        return getAllClaimsFromToken(token).getSubject();
    }

    private Claims getAllClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private boolean isTokenExpired(String token) {
        final Date tokenExpiration = getAllClaimsFromToken(token).getExpiration();
        return tokenExpiration.before(new Date());
    }
}
