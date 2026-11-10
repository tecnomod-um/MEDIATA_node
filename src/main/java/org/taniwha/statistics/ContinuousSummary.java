package org.taniwha.statistics;

import org.apache.commons.math3.distribution.TDistribution;
import org.taniwha.util.AggregateDisclosure;

import java.util.List;
import java.util.ArrayList;

/** Additional numerical summaries, calculated inside the node on the observed cohort. */
public record ContinuousSummary(Double sumCubedDeviations, Double sumFourthDeviations,
                                Double sampleStdDev, Double skewness, Double kurtosis,
                                Double meanCiLower, Double meanCiUpper,
                                List<Double> modes, Long modeFrequency, String modeStatus) {
    public static ContinuousSummary calculateSorted(List<Double> values, double mean, double populationSd) {
        int n = values.size();
        double m3 = 0, correction = 0, m4 = 0, fourthCorrection = 0;
        for (double value : values) {
            double delta = value - mean;
            double term = delta * delta * delta - correction;
            double next = m3 + term;
            correction = (next - m3) - term;
            m3 = next;
            double squared = delta * delta;
            double fourthTerm = squared * squared - fourthCorrection;
            double fourthNext = m4 + fourthTerm;
            fourthCorrection = (fourthNext - m4) - fourthTerm;
            m4 = fourthNext;
        }
        Double skewness = n > 2 && populationSd > 0
                ? finite((m3 / n / populationSd / populationSd / populationSd)
                    * (Math.sqrt((double) n * (n - 1)) / (n - 2))) : null;
        Double sampleSd = n > 1 ? finite(populationSd * Math.sqrt((double) n / (n - 1))) : null;
        double excess = m4 / n / populationSd / populationSd / populationSd / populationSd - 3;
        Double kurtosis = n > 3 && populationSd > 0
                ? finite(((double) (n - 1) / (n - 2) / (n - 3)) * ((n + 1.0) * excess + 6)) : null;
        Double lower = null, upper = null;
        if (n > 1 && Double.isFinite(mean) && Double.isFinite(populationSd)) {
            double margin = new TDistribution(n - 1).inverseCumulativeProbability(0.975)
                    * (populationSd / Math.sqrt(n - 1));
            lower = finite(mean - margin);
            upper = finite(mean + margin);
        }
        // Analytics already sorts for quantiles. Scan runs instead of allocating a
        // second frequency map proportional to the number of distinct patient values.
        long frequency = 0;
        List<Double> modes = new ArrayList<>();
        for (int first = 0; first < n;) {
            double value = values.get(first);
            int end = first + 1;
            while (end < n && values.get(end) == value) end++;
            long run = end - first;
            if (run > frequency) { frequency = run; modes.clear(); }
            if (run == frequency && modes.size() <= AggregateDisclosure.MAX_CELLS) modes.add(value == 0 ? 0.0 : value);
            first = end;
        }
        boolean repeated = frequency > 1;
        boolean releasable = frequency >= AggregateDisclosure.MIN_COUNT;
        boolean tooMany = modes.size() > AggregateDisclosure.MAX_CELLS;
        return new ContinuousSummary(finite(m3), finite(m4), sampleSd, skewness, kurtosis,
                lower, upper, releasable && !tooMany ? List.copyOf(modes) : List.of(),
                releasable && !tooMany ? frequency : null,
                !repeated ? "No repeated value" : tooMany ? "Withheld: too many tied modes" : releasable ? "Available"
                    : "Withheld: modal frequency below release threshold");
    }

    private static Double finite(double value) { return Double.isFinite(value) ? value : null; }
}
