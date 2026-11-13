package org.taniwha.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.taniwha.config.RestTemplateHolder;
import org.taniwha.util.JwtTokenUtil;
import org.springframework.web.client.RestTemplate;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for JWT utility bean.
 * Tests JWT token generation and validation in Spring context.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "jwt.secret=test-secret-key-must-be-256-bits-or-more-for-security-purposes-32chars",
    "jwt.expiration=3600000",
    "app.path=/tmp/test-taniwha",
    "spring.main.allow-bean-definition-overriding=true"
})
class JwtTokenUtilIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public RestTemplate restTemplate() {
            return mock(RestTemplate.class);
        }
        
        @Bean
        public Supplier<RestTemplate> restTemplateSupplier() {
            RestTemplate mockRestTemplate = mock(RestTemplate.class);
            return () -> mockRestTemplate;
        }
        
        @Bean
        public RestTemplateHolder restTemplateHolder() {
            return mock(RestTemplateHolder.class);
        }
    }

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    @Test
    void jwtTokenGenerationWorks() {
        String subject = "test-user";
        
        // Generate token
        String token = jwtTokenUtil.generateToken(subject);
        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3); // JWT has 3 parts
    }

    @Test
    void jwtTokenValidationWorks() {
        String token = jwtTokenUtil.generateToken("user");
        
        // Token should be valid initially
        assertThat(jwtTokenUtil.validateToken(token)).isTrue();
    }

    @Test
    void multipleTokensAreUnique() {
        String token1 = jwtTokenUtil.generateToken("user1");
        String token2 = jwtTokenUtil.generateToken("user2");
        
        assertThat(token1).isNotEqualTo(token2);
        assertThat(jwtTokenUtil.validateToken(token1)).isTrue();
        assertThat(jwtTokenUtil.validateToken(token2)).isTrue();
    }

    @Test
    void jwtTokenWithSpecialCharacters() {
        String subject = "user@example.com";
        String token = jwtTokenUtil.generateToken(subject);
        
        assertThat(jwtTokenUtil.validateToken(token)).isTrue();
    }

    @Test
    void jwtTokenThreadSafety() throws InterruptedException {
        Thread[] threads = new Thread[10];
        String[] tokens = new String[10];
        
        for (int i = 0; i < 10; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                tokens[index] = jwtTokenUtil.generateToken("user" + index);
            });
            threads[i].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        // All tokens should be valid and unique
        for (int i = 0; i < 10; i++) {
            assertThat(tokens[i]).isNotBlank();
            assertThat(jwtTokenUtil.validateToken(tokens[i])).isTrue();
        }
    }
}
