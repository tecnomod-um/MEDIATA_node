package org.taniwha.util;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import static org.assertj.core.api.Assertions.*;

class JwtTokenUtilTest {

    private JwtTokenUtil loadWith(String secret, String expirationSeconds) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                ctx,
                "jwt.secret=" + secret,
                "jwt.expiration=" + expirationSeconds
        );
        ctx.register(JwtTokenUtil.class);
        ctx.refresh();
        return ctx.getBean(JwtTokenUtil.class);
    }

    @Test
    void generate_parse_and_validate_token() {
        // Use a 256-bit (32 character) secret key as required by modern JWT
        JwtTokenUtil util = loadWith("myVerySecretKeyThatIsAtLeast32BytesLongForSecurityPurposes", "3600");

        String token = util.generateToken("alice");
        assertThat(token).isNotBlank();

        String subject = util.getUsernameFromToken(token);
        assertThat(subject).isEqualTo("alice");

        assertThat(util.validateToken(token)).isTrue();
    }

    @Test
    void generateToken_shouldProduceValidJWTStructure() {
        JwtTokenUtil util = loadWith("myVerySecretKeyThatIsAtLeast32BytesLongForSecurityPurposes", "3600");

        String token = util.generateToken("testUser");
        
        // JWT should have 3 parts separated by dots: header.payload.signature
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isNotEmpty(); // header
        assertThat(parts[1]).isNotEmpty(); // payload
        assertThat(parts[2]).isNotEmpty(); // signature
    }

    @Test
    void generateToken_withDifferentSubjects_shouldProduceDifferentTokens() {
        JwtTokenUtil util = loadWith("myVerySecretKeyThatIsAtLeast32BytesLongForSecurityPurposes", "3600");

        String token1 = util.generateToken("alice");
        String token2 = util.generateToken("bob");

        assertThat(token1).isNotEqualTo(token2);
    }

    @Test
    void generateToken_withSameSubject_shouldProduceDifferentTokensDueToTimestamp() throws InterruptedException {
        JwtTokenUtil util = loadWith("myVerySecretKeyThatIsAtLeast32BytesLongForSecurityPurposes", "3600");

        String token1 = util.generateToken("alice");
        Thread.sleep(1100); // Ensure different timestamps (must be > 1 second for iat claim)
        String token2 = util.generateToken("alice");

        // Tokens should differ because issuedAt timestamp changes
        assertThat(token1).isNotEqualTo(token2);
    }

    @Test
    void getUsernameFromToken_withValidToken_shouldExtractSubject() {
        JwtTokenUtil util = loadWith("myVerySecretKeyThatIsAtLeast32BytesLongForSecurityPurposes", "3600");

        String token = util.generateToken("testUser");
        String extractedSubject = util.getUsernameFromToken(token);

        assertThat(extractedSubject).isEqualTo("testUser");
    }

    @Test
    void validateToken_withDifferentSecret_shouldFail() {
        JwtTokenUtil util1 = loadWith("myVerySecretKeyThatIsAtLeast32BytesLongForSecurityPurposes", "3600");
        JwtTokenUtil util2 = loadWith("aDifferentSecretKeyThatIsAtLeast32BytesLongForSecurity", "3600");

        String token = util1.generateToken("alice");

        // Token signed with util1's secret should not validate with util2's secret
        assertThatThrownBy(() -> util2.validateToken(token))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void expired_token_throwsExpiredJwtException() throws InterruptedException {
        // Use a 256-bit (32 character) secret key as required by modern JWT
        JwtTokenUtil util = loadWith("anotherSecretKeyThatIsAtLeast32BytesLongForSecurityPurposes", "0");

        String token = util.generateToken("eve");
        Thread.sleep(10);
        assertThatThrownBy(() -> util.validateToken(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void validateToken_withMalformedToken_shouldThrowException() {
        JwtTokenUtil util = loadWith("myVerySecretKeyThatIsAtLeast32BytesLongForSecurityPurposes", "3600");

        assertThatThrownBy(() -> util.validateToken("not.a.valid.jwt"))
                .isInstanceOf(Exception.class); // Could be SignatureException or MalformedJwtException
    }

    @Test
    void validateToken_withEmptyToken_shouldThrowException() {
        JwtTokenUtil util = loadWith("myVerySecretKeyThatIsAtLeast32BytesLongForSecurityPurposes", "3600");

        assertThatThrownBy(() -> util.validateToken(""))
                .isInstanceOf(Exception.class);
    }

    @Test
    void validateToken_withLongExpirationTime_shouldBeValid() {
        JwtTokenUtil util = loadWith("myVerySecretKeyThatIsAtLeast32BytesLongForSecurityPurposes", "86400"); // 24 hours

        String token = util.generateToken("alice");

        assertThat(util.validateToken(token)).isTrue();
    }

    @Test
    void getUsernameFromToken_withSpecialCharacters_shouldHandleCorrectly() {
        JwtTokenUtil util = loadWith("myVerySecretKeyThatIsAtLeast32BytesLongForSecurityPurposes", "3600");

        String specialSubject = "user@example.com";
        String token = util.generateToken(specialSubject);
        String extractedSubject = util.getUsernameFromToken(token);

        assertThat(extractedSubject).isEqualTo(specialSubject);
    }
}
