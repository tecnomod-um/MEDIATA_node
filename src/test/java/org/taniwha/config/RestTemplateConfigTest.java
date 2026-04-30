package org.taniwha.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestTemplateConfigTest {

    @Test
    void restTemplate_whenTlsProbeDisabled_returnsDefaultRestTemplate() {
        RestTemplateConfig config = new RestTemplateConfig();
        ReflectionTestUtils.setField(config, "tlsProbeEnabled", false);

        RestTemplate restTemplate = config.restTemplate();

        assertThat(restTemplate).isNotNull();
    }

    @Test
    void restTemplateSupplier_whenTlsProbeDisabled_returnsNewInstanceEachCall() {
        RestTemplateConfig config = new RestTemplateConfig();
        ReflectionTestUtils.setField(config, "tlsProbeEnabled", false);

        Supplier<RestTemplate> supplier = config.restTemplateSupplier();
        RestTemplate first = supplier.get();
        RestTemplate second = supplier.get();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void restTemplateSupplier_whenTlsProbeEnabledAndInvalidPort_wrapsFailure() {
        RestTemplateConfig config = new RestTemplateConfig();
        ReflectionTestUtils.setField(config, "tlsProbeEnabled", true);
        ReflectionTestUtils.setField(config, "targetHost", "localhost");
        ReflectionTestUtils.setField(config, "targetPort", -1);

        Supplier<RestTemplate> supplier = config.restTemplateSupplier();

        assertThatThrownBy(supplier::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to build RestTemplate")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
