package org.taniwha.config;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Component
public class RestTemplateHolder {
    private final Supplier<RestTemplate> supplier;
    private final AtomicReference<RestTemplate> restTemplate;

    public RestTemplateHolder(Supplier<RestTemplate> supplier) {
        this.supplier = supplier;
        this.restTemplate = new AtomicReference<>(supplier.get());
    }

    public RestTemplate get() {
        return restTemplate.get();
    }

    public void refresh() {
        this.restTemplate.set(supplier.get());
    }
}
