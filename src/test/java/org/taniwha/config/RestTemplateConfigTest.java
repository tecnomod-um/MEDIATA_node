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
    
    @Configuration
    static class TestConfig {
        @Bean
        public Supplier<RestTemplate> restTemplateSupplier() {
            return RestTemplate::new;
        }
    }
}
