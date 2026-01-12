package org.taniwha.util;

import io.jsonwebtoken.ExpiredJwtException;
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
    void expired_token_throwsExpiredJwtException() throws InterruptedException {
        // Use a 256-bit (32 character) secret key as required by modern JWT
        JwtTokenUtil util = loadWith("anotherSecretKeyThatIsAtLeast32BytesLongForSecurityPurposes", "0");

        String token = util.generateToken("eve");
        Thread.sleep(10);
        assertThatThrownBy(() -> util.validateToken(token))
                .isInstanceOf(ExpiredJwtException.class);
    }
}
