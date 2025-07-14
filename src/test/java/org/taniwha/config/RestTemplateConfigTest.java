package org.taniwha.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.client.RestTemplate;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

class RestTemplateConfigTest {

    @Test
    void restTemplateSupplier_and_restTemplate_beans_are_created() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(RestTemplateConfig.class);
            ctx.refresh();

            @SuppressWarnings("unchecked")
            Supplier<RestTemplate> supplier =
                    (Supplier<RestTemplate>) ctx.getBean("restTemplateSupplier");
            assertThat(supplier)
                    .as("restTemplateSupplier bean")
                    .isNotNull();

            RestTemplate rtFromSupplier = supplier.get();
            assertThat(rtFromSupplier)
                    .as("supplier.get() returns RestTemplate")
                    .isInstanceOf(RestTemplate.class);

            RestTemplate rtBean = ctx.getBean(RestTemplate.class);
            assertThat(rtBean)
                    .as("restTemplate bean")
                    .isInstanceOf(RestTemplate.class);
            assertThat(rtFromSupplier).isNotNull();
            assertThat(rtBean).isNotNull();
        }
    }
}
