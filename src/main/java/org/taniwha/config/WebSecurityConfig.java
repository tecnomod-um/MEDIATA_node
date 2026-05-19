package org.taniwha.config;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.taniwha.security.JwtRequestFilter;
import org.taniwha.security.TrustedProxyRequestFilter;
import org.taniwha.security.TrustedProxyResponseSigningFilter;

import java.util.Collections;

@Configuration
@EnableWebSecurity
@EnableAutoConfiguration(exclude = {UserDetailsServiceAutoConfiguration.class})
public class WebSecurityConfig {

    private final JwtRequestFilter jwtRequestFilter;
    private final TrustedProxyRequestFilter trustedProxyRequestFilter;
    private final TrustedProxyResponseSigningFilter trustedProxyResponseSigningFilter;

    public WebSecurityConfig(JwtRequestFilter jwtRequestFilter,
                             TrustedProxyRequestFilter trustedProxyRequestFilter,
                             TrustedProxyResponseSigningFilter trustedProxyResponseSigningFilter) {
        this.jwtRequestFilter = jwtRequestFilter;
        this.trustedProxyRequestFilter = trustedProxyRequestFilter;
        this.trustedProxyResponseSigningFilter = trustedProxyResponseSigningFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS).permitAll()
                        .requestMatchers(
                                "/node/authorize",
                                "/node/validate",
                                "/node/health",
                                "/node/metadata",
                                "/fdp",
                                "/fdp/**",
                                "/api/user/login",
                                "/api/user/register",
                                "/api/error",
                                "/nodes/register",
                                "/nodes/heartbeat"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                );
        http.addFilterBefore(trustedProxyRequestFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(jwtRequestFilter, TrustedProxyRequestFilter.class);
        http.addFilterAfter(trustedProxyResponseSigningFilter, JwtRequestFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(Collections.singletonList("*"));
        configuration.setAllowedMethods(Collections.singletonList("*"));
        configuration.setAllowedHeaders(Collections.singletonList("*"));
        configuration.setExposedHeaders(Collections.singletonList("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
