package org.taniwha.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtTokenUtil {

    private static final String NODE_SYNC_ISSUER = "mediata-node";
    private static final String NODE_SYNC_AUDIENCE = "mediata-orchestrator";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    @Value("${node.sync.jwt.expiration:120}")
    private long nodeSyncExpiration;

    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(String subject) {
        return generateToken(subject, Map.of());
    }

    public String generateNodeAccessToken(String subject) {
        return generateToken(subject, Map.of("token_use", "node_access"));
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
        return hasClaimValue(token, "token_use", "node_access");
    }

    public boolean validateToken(String token) {
        return !isTokenExpired(token);
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
