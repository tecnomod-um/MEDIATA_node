package org.taniwha.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.taniwha.security.JwtRequestFilter;
import org.taniwha.security.TrustedProxyRequestFilter;
import org.taniwha.security.TrustedProxyResponseSigningFilter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebSecurityConfigTest {

    @Test
    void securityBeans_and_corsConfiguration_areCreatedCorrectly() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(JwtRequestFilter.class, () -> mock(JwtRequestFilter.class));
            ctx.registerBean(TrustedProxyRequestFilter.class, () -> mock(TrustedProxyRequestFilter.class));
            ctx.registerBean(TrustedProxyResponseSigningFilter.class, () -> mock(TrustedProxyResponseSigningFilter.class));
            ctx.register(WebSecurityConfig.class);
            ctx.refresh();
            SecurityFilterChain chain = ctx.getBean(SecurityFilterChain.class);
            assertThat(chain).as("securityFilterChain").isNotNull();

            PasswordEncoder encoder = ctx.getBean(PasswordEncoder.class);
            assertThat(encoder).isInstanceOf(BCryptPasswordEncoder.class);
            CorsConfigurationSource source = ctx.getBean(CorsConfigurationSource.class);
            assertThat(source).isInstanceOf(UrlBasedCorsConfigurationSource.class);
            var urlSrc = (UrlBasedCorsConfigurationSource) source;

            Map<String, CorsConfiguration> configs = urlSrc.getCorsConfigurations();
            CorsConfiguration cors = configs.get("/**");
            assertThat(cors).isNotNull();
            assertThat(cors.getAllowedOriginPatterns()).containsExactly("*");
            assertThat(cors.getAllowedMethods()).containsExactly("*");
            assertThat(cors.getAllowedHeaders()).containsExactly("*");
            assertThat(cors.getExposedHeaders()).containsExactly("*");
            assertThat(cors.getAllowCredentials()).isTrue();
        }
    }
}
