package org.taniwha.privacy.pooling;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

// rowEpsilon accounts for adding or removing one row in the protected histograms.
// Disjoint groups compose in parallel, features compose sequentially.
// This budget does not account for the separate exact-statistics endpoints.
record PrivatePoolingNoisePlan(Map<String, PrivateHistogramNoise> samplers, double rowEpsilon) {
    static PrivatePoolingNoisePlan forFeatures(Map<String, PrivatePoolingDomain> features, SecureRandom random, double allowance) {
        if (features.isEmpty() || !Double.isFinite(allowance) || allowance <= 0)
            throw new IllegalArgumentException("Invalid protected-summary allowance");
        // Round continuation upward, so finite rational sampling never exceeds
        // the configured total allowance. All feature types receive equal shares.
        int denominator = 1024;
        int numerator = Math.max(1, (int) Math.ceil(Math.exp(-allowance / features.size()) * denominator));
        while (numerator < denominator && features.size() * Math.log((double) denominator / numerator) > allowance) numerator++;
        if (numerator >= denominator) {
            throw new PrivatePoolingUnavailableException("dataset-budget-exceeded",
                    "The configured allowance is too small to release this feature set with the supported integer mechanism.");
        }
        var sampler = new PrivateHistogramNoise(random, numerator, denominator);
        Map<String, PrivateHistogramNoise> samplers = new LinkedHashMap<>();
        features.keySet().forEach(name -> samplers.put(name, sampler));
        return new PrivatePoolingNoisePlan(Map.copyOf(samplers), features.size() * Math.log((double) denominator / numerator));
    }
}
