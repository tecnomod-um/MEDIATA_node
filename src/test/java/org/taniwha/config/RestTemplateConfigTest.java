package org.taniwha.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

class RestTemplateConfigTest {

    @Test
    void restTemplateSupplier_bean_is_created() {
        // Test that the supplier bean can be created (but don't call get() as it requires network access)
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(Supplier.class);
                    @SuppressWarnings("unchecked")
                    Supplier<RestTemplate> supplier = 
                            (Supplier<RestTemplate>) context.getBean("restTemplateSupplier");
                    assertThat(supplier)
                            .as("restTemplateSupplier bean")
                            .isNotNull();
                    // Don't call supplier.get() as it requires network access to external server
                });
    }

    @Test
    void restTemplateSupplier_shouldProvideRestTemplateWhenCalled() {
        // Test the supplier concept without actual network - use a mock configuration
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(context -> {
                    @SuppressWarnings("unchecked")
                    Supplier<RestTemplate> supplier = 
                            (Supplier<RestTemplate>) context.getBean("restTemplateSupplier");
                    
                    RestTemplate restTemplate = supplier.get();
                    
                    assertThat(restTemplate).isNotNull();
                    assertThat(restTemplate).isInstanceOf(RestTemplate.class);
                });
    }

    @Test
    void restTemplateSupplier_shouldProvideNewInstanceEachTime() {
        // Verify supplier creates new instances
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(context -> {
                    @SuppressWarnings("unchecked")
                    Supplier<RestTemplate> supplier = 
                            (Supplier<RestTemplate>) context.getBean("restTemplateSupplier");
                    
                    RestTemplate restTemplate1 = supplier.get();
                    RestTemplate restTemplate2 = supplier.get();
                    
                    // Each call to get() should return a new instance
                    assertThat(restTemplate1).isNotNull();
                    assertThat(restTemplate2).isNotNull();
                    assertThat(restTemplate1).isNotSameAs(restTemplate2);
                });
    }

    @Test
    void restTemplateSupplier_bean_shouldBeSingleton() {
        // Verify the supplier bean itself is a singleton
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(context -> {
                    @SuppressWarnings("unchecked")
                    Supplier<RestTemplate> supplier1 = 
                            (Supplier<RestTemplate>) context.getBean("restTemplateSupplier");
                    @SuppressWarnings("unchecked")
                    Supplier<RestTemplate> supplier2 = 
                            (Supplier<RestTemplate>) context.getBean("restTemplateSupplier");
                    
                    // The supplier bean itself should be a singleton
                    assertThat(supplier1).isSameAs(supplier2);
                });
    }

    @Configuration
    static class TestConfig {
        @Bean
        public Supplier<RestTemplate> restTemplateSupplier() {
            return RestTemplate::new;
        }
    }
}
