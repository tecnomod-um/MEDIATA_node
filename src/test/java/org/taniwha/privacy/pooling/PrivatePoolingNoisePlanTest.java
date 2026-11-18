package org.taniwha.privacy.pooling;

import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PrivatePoolingNoisePlanTest {
    @Test void increasingFeatureCountDividesTheAllowanceWithoutRaisingTheTotal() {
        double allowance = 4 * Math.log(2);
        for (int count : List.of(1, 2, 12, 64, 256)) {
            var features = new LinkedHashMap<String, PrivatePoolingDomain>();
            IntStream.range(0, count).forEach(i -> features.put("Measurement " + i,
                    new PrivatePoolingDomain(null, List.of("Listed"))));
            var plan = PrivatePoolingNoisePlan.forFeatures(features, new SecureRandom(), allowance);
            double variance = plan.samplers().values().iterator().next().variance();
            double q = variance / (variance + 1 + Math.sqrt(2 * variance + 1));
            assertThat(-count * Math.log(q)).isCloseTo(plan.rowEpsilon(), within(1e-10));
            assertThat(plan.rowEpsilon()).isPositive().isLessThanOrEqualTo(allowance);
            assertThat(plan.samplers().values()).allSatisfy(sampler -> assertThat(sampler.variance()).isEqualTo(variance));
        }
    }

    @Test void sampledNoiseMatchesItsDeclaredMeanAndVariance() throws Exception {
        var random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(783423L);
        var sampler = new PrivateHistogramNoise(random, 8, 9);
        int samples = 50000;
        double sum = 0, squares = 0;
        for (int i = 0; i < samples; i++) {
            double noise = sampler.release(100) - 100;
            sum += noise;
            squares += noise * noise;
        }
        double mean = sum / samples;
        assertThat(mean).isCloseTo(0, within(.4));
        assertThat(squares / samples - mean * mean).isCloseTo(sampler.variance(), within(sampler.variance() * .08));
    }
}
