package org.taniwha.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

class RestTemplateHolderTest {

    @Test
    void constructor_initializesRestTemplateFromSupplier() {
        RestTemplate rt = new RestTemplate();
        Supplier<RestTemplate> supplier = () -> rt;

        RestTemplateHolder holder = new RestTemplateHolder(supplier);
        assertThat(holder.get()).isSameAs(rt);
    }

    @Test
    void refresh_replacesRestTemplateWithNewInstance() {
        RestTemplate rt1 = new RestTemplate();
        RestTemplate rt2 = new RestTemplate();
        AtomicInteger idx = new AtomicInteger(0);
        List<RestTemplate> sequence = List.of(rt1, rt2);

        Supplier<RestTemplate> supplier = () -> sequence.get(idx.getAndIncrement());
        RestTemplateHolder holder = new RestTemplateHolder(supplier);

        assertThat(idx.get()).isEqualTo(1);
        assertThat(holder.get()).isSameAs(rt1);

        holder.refresh();
        assertThat(idx.get()).isEqualTo(2);
        assertThat(holder.get()).isSameAs(rt2);
    }

    @Test
    void refresh_canBeCalledMultipleTimes() {
        RestTemplate rt1 = new RestTemplate();
        RestTemplate rt2 = new RestTemplate();
        RestTemplate rt3 = new RestTemplate();
        AtomicInteger idx = new AtomicInteger(0);
        List<RestTemplate> sequence = List.of(rt1, rt2, rt3);

        Supplier<RestTemplate> supplier = () -> sequence.get(idx.getAndIncrement());
        RestTemplateHolder holder = new RestTemplateHolder(supplier);

        assertThat(holder.get()).isSameAs(rt1);

        holder.refresh();
        assertThat(holder.get()).isSameAs(rt2);

        holder.refresh();
        assertThat(holder.get()).isSameAs(rt3);
    }
}
