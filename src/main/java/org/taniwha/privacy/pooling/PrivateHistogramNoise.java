package org.taniwha.privacy.pooling;

import java.math.BigInteger;
import java.security.SecureRandom;

final class PrivateHistogramNoise {
    // Preserve a bounded wire representation by clamping after sampling the integer mechanism.
    private static final BigInteger LIMIT = BigInteger.valueOf(1_000_000_000L);
    private final SecureRandom random;
    private final int geometricDivisor;

    PrivateHistogramNoise(SecureRandom random, int features) {
        this.random = random;
        geometricDivisor = divisor(features);
    }

    long release(long count) {
        return BigInteger.valueOf(count).min(LIMIT).add(geometric()).subtract(geometric())
                .max(LIMIT.negate()).min(LIMIT).longValueExact();
    }

    static double epsilon(int features) {
        return features * Math.log1p(1.0 / (divisor(features) - 1));
    }

    double variance() {
        return 2.0 * geometricDivisor * (geometricDivisor - 1);
    }

    private BigInteger geometric() {
        BigInteger value = BigInteger.ZERO;
        while (random.nextInt(geometricDivisor) != 0) value = value.add(BigInteger.ONE);
        return value;
    }

    private static int divisor(int features) {
        return features <= 4 ? 2 : features <= 8 ? 4 : 8;
    }
}
