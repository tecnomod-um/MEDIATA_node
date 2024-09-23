package org.taniwha.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

// Requests will be tried over multiple times
@Configuration
public class RetryConfig {

    @Value("${retry.max.attempts}")
    private int retryMaxAttempts;

    @Value("${retry.backoff.period.ms}")
    private long retryBackoffPeriod;

    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(retryMaxAttempts));
        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(retryBackoffPeriod);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        return retryTemplate;
    }
}
