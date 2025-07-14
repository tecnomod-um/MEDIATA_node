package org.taniwha.config;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.function.Supplier;

@Component
public class RestTemplateHolder {
    private final Supplier<RestTemplate> supplier;
    private volatile RestTemplate restTemplate;

    public RestTemplateHolder(Supplier<RestTemplate> supplier) {
        this.supplier = supplier;
        this.restTemplate = supplier.get();
    }

    public RestTemplate get() {
        return restTemplate;
    }

    public void refresh() {
        this.restTemplate = supplier.get();
    }
}
