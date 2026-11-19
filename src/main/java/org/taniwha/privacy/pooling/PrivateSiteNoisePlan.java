package org.taniwha.privacy.pooling;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/** Allocate noise by public histogram sizes, including an independent cohort count. */
record PrivateSiteNoisePlan(Map<String, PrivateHistogramNoise> samplers,
                            PrivateHistogramNoise cohort, double rowEpsilon) {
    private static final int DENOMINATOR = 1 << 30;
    private static final double MIN_EPSILON = Math.log(1024.0 / 1023);

    static PrivateSiteNoisePlan create(Map<String, PrivatePoolingDomain> features, SecureRandom random, double allowance) {
        int channels = features.size() + 1;
        if (features.isEmpty() || !Double.isFinite(allowance) || allowance < channels * MIN_EPSILON)
            throw new PrivatePoolingUnavailableException("dataset-budget-exceeded", "The allowance is too small for this feature set and its cohort count.");
        // For geometric noise, k exp(-epsilon) approximates total absolute
        // error over k cells. Water filling gives epsilon = log(k) + offset,
        // bounded below to avoid pathological sampling costs.
        var sizes = new LinkedHashMap<String, Integer>();
        features.forEach((name, domain) -> {
            int cells = 0;
            for (int cell = 0; cell < domain.size(); cell++) if (!domain.structuralZero(cell)) cells++;
            sizes.put(name, Math.max(1, cells));
        });
        double low = -32, high = allowance;
        for (int iteration = 0; iteration < 100; iteration++) {
            double offset = (low + high) / 2;
            double cost = Math.max(MIN_EPSILON, offset);
            for (int size : sizes.values()) cost += Math.max(MIN_EPSILON, Math.log(size) + offset);
            if (cost > allowance) high = offset; else low = offset;
        }
        var samplers = new LinkedHashMap<String, PrivateHistogramNoise>();
        double cost = 0;
        for (var entry : sizes.entrySet()) {
            double epsilon = Math.max(MIN_EPSILON, Math.log(entry.getValue()) + low);
            var sampler = sampler(random, epsilon);
            samplers.put(entry.getKey(), sampler);
            cost += sampler.epsilon();
        }
        var cohort = sampler(random, Math.max(MIN_EPSILON, low));
        cost += cohort.epsilon();
        if (cost > allowance + 1e-9) throw new IllegalStateException("Protected release exceeds its allowance");
        return new PrivateSiteNoisePlan(Map.copyOf(samplers), cohort, cost);
    }

    private static PrivateHistogramNoise sampler(SecureRandom random, double epsilon) {
        int numerator = Math.max(1, (int) Math.ceil(Math.exp(-epsilon) * DENOMINATOR));
        return new PrivateHistogramNoise(random, numerator, DENOMINATOR);
    }
}
