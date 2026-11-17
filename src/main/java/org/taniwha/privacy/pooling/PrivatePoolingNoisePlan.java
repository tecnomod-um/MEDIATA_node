package org.taniwha.privacy.pooling;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

record PrivatePoolingNoisePlan(Map<String, PrivateHistogramNoise> samplers, double rowEpsilon) {
    private static final double LOG_TWO = Math.log(2);

    static PrivatePoolingNoisePlan forFeatures(Map<String, PrivatePoolingDomain> features, SecureRandom random) {
        long numerical = features.values().stream().filter(domain -> domain.boundaries() != null).count();
        long categorical = features.size() - numerical;
        boolean prioritizeMeans = numerical > 0 && numerical <= categorical;
        Map<String, PrivateHistogramNoise> samplers = new LinkedHashMap<>();
        double epsilon;
        if (prioritizeMeans) {
            var numericNoise = new PrivateHistogramNoise(random, 2, 5);
            var categoricalNoise = new PrivateHistogramNoise(random, 5, 8);
            features.forEach((name, domain) -> samplers.put(name,
                    domain.boundaries() != null ? numericNoise : categoricalNoise));
            // A numerical/categorical pair has continuation product (2/5)(5/8) = 1/4.
            // Its cost is the same as two features at the old probability of 1/2.
            epsilon = 2 * numerical * LOG_TWO + (categorical - numerical) * Math.log(8.0 / 5);
        } else {
            int divisor = features.size() <= 4 ? 2 : features.size() <= 8 ? 4 : 8;
            var uniform = new PrivateHistogramNoise(random, divisor - 1, divisor);
            features.keySet().forEach(name -> samplers.put(name, uniform));
            epsilon = features.size() * Math.log1p(1.0 / (divisor - 1));
        }
        return new PrivatePoolingNoisePlan(Map.copyOf(samplers), epsilon);
    }
}
