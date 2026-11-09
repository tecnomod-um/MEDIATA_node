package org.taniwha.statistics;

import java.util.List;

/** Complete-case sufficient statistics. Centred moments avoid unstable raw sums. */
public record NumericPairSummary(String featureX, String featureY, long count,
        double meanX, double meanY, double m2X, double m2Y, double crossMoment,
        List<Cell> frequencies) {
    public record Cell(double x, double y, long count) {}
}
