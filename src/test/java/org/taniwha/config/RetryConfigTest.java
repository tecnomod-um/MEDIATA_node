package org.taniwha.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

class RetryConfigTest {

    @Test
    void retryTemplate_usesConfiguredPolicies() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                ctx,
                "retry.max.attempts=7",
                "retry.backoff.period.ms=250"
        );
        ctx.register(RetryConfig.class);
        ctx.refresh();

        RetryTemplate tpl = ctx.getBean(RetryTemplate.class);
        assertThat(tpl).isNotNull();
        Object retryPolicy = ReflectionTestUtils.getField(tpl, "retryPolicy");
        assertThat(retryPolicy).isInstanceOf(SimpleRetryPolicy.class);
        SimpleRetryPolicy sp = (SimpleRetryPolicy) retryPolicy;
        assert sp != null;
        assertThat(sp.getMaxAttempts()).isEqualTo(7);
        Object backOffPolicy = ReflectionTestUtils.getField(tpl, "backOffPolicy");
        assertThat(backOffPolicy).isInstanceOf(FixedBackOffPolicy.class);
        FixedBackOffPolicy fp = (FixedBackOffPolicy) backOffPolicy;
        assert fp != null;
        assertThat(fp.getBackOffPeriod()).isEqualTo(250L);
        ctx.close();
    }
}
