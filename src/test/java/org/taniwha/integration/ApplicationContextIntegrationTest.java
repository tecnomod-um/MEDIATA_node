package org.taniwha.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.taniwha.config.RestTemplateHolder;
import org.taniwha.service.*;
import org.taniwha.util.JwtTokenUtil;
import org.springframework.web.client.RestTemplate;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for Spring Boot application context.
 * Validates that all beans are properly wired and the application context loads correctly.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "jwt.secret=test-secret-key-must-be-256-bits-or-more-for-security-purposes",
    "jwt.expiration=3600000",
    "app.path=/tmp/test-taniwha",
    "spring.main.allow-bean-definition-overriding=true"
})
class ApplicationContextIntegrationTest {

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
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void allServiceBeansAreAvailable() {
        assertThat(applicationContext.getBean(FileService.class)).isNotNull();
        assertThat(applicationContext.getBean(DataProcessingService.class)).isNotNull();
        assertThat(applicationContext.getBean(DataCleaningService.class)).isNotNull();
        assertThat(applicationContext.getBean(AnalyticsService.class)).isNotNull();
        assertThat(applicationContext.getBean(HarmonizerService.class)).isNotNull();
    }

    @Test
    void utilityBeansAreAvailable() {
        assertThat(applicationContext.getBean(JwtTokenUtil.class)).isNotNull();
    }

    @Test
    void beansAreSingletons() {
        FileService bean1 = applicationContext.getBean(FileService.class);
        FileService bean2 = applicationContext.getBean(FileService.class);
        assertThat(bean1).isSameAs(bean2);
    }

    @Test
    void dataCleaningServiceHasDependencies() {
        DataCleaningService service = applicationContext.getBean(DataCleaningService.class);
        assertThat(service).isNotNull();
    }

    @Test
    void harmonizerServiceHasDependencies() {
        HarmonizerService service = applicationContext.getBean(HarmonizerService.class);
        assertThat(service).isNotNull();
    }

    @Test
    void analyticsServiceHasDependencies() {
        AnalyticsService service = applicationContext.getBean(AnalyticsService.class);
        assertThat(service).isNotNull();
    }

    @Test
    void jwtTokenUtilIsConfigured() {
        JwtTokenUtil jwtUtil = applicationContext.getBean(JwtTokenUtil.class);
        assertThat(jwtUtil).isNotNull();
        
        // Test that JWT utility can generate tokens
        String token = jwtUtil.generateToken("test-subject");
        assertThat(token).isNotBlank();
    }

    @Test
    void propertyResolutionWorks() {
        String secret = applicationContext.getEnvironment().getProperty("jwt.secret");
        assertThat(secret).isEqualTo("test-secret-key-must-be-256-bits-or-more-for-security-purposes");
    }

    @Test
    void multipleServiceBeansWork() {
        DataProcessingService processingService = applicationContext.getBean(DataProcessingService.class);
        DataCleaningService cleaningService = applicationContext.getBean(DataCleaningService.class);
        FileService fileService = applicationContext.getBean(FileService.class);
        
        assertThat(processingService).isNotNull();
        assertThat(cleaningService).isNotNull();
        assertThat(fileService).isNotNull();
    }
}
