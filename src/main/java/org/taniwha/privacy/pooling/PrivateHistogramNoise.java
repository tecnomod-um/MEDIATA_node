package org.taniwha.privacy.pooling;

import java.math.BigInteger;
import java.security.SecureRandom;

final class PrivateHistogramNoise {
    // Preserve a bounded wire representation by clamping after sampling the integer mechanism.
    private static final BigInteger LIMIT = BigInteger.valueOf(1_000_000_000L);
    private final SecureRandom random;
    private final int continuationNumerator;
    private final int continuationDenominator;

    PrivateHistogramNoise(SecureRandom random, int numerator, int denominator) {
        this.random = random;
        continuationNumerator = numerator;
        continuationDenominator = denominator;
    }

    long release(long count) {
        return BigInteger.valueOf(count).min(LIMIT).add(geometric()).subtract(geometric())
                .max(LIMIT.negate()).min(LIMIT).longValueExact();
    }

    double variance() {
        int stopNumerator = continuationDenominator - continuationNumerator;
        return 2.0 * continuationNumerator * continuationDenominator / (stopNumerator * stopNumerator);
    }

    private BigInteger geometric() {
        BigInteger value = BigInteger.ZERO;
        while (random.nextInt(continuationDenominator) < continuationNumerator) value = value.add(BigInteger.ONE);
        return value;
    }
}
