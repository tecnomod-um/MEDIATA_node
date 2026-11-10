package org.taniwha.statistics;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ContinuousSummaryTest {
    private ContinuousSummary calculate(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sd = Math.sqrt(values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / values.size());
        return ContinuousSummary.calculateSorted(values.stream().sorted().toList(), mean, sd);
    }

    @Test void matchesIndependentStudentTAndAdjustedSkewnessReferenceValues() {
        var summary = calculate(List.of(1., 2., 3., 4., 8., 8.));
        assertThat(summary.sumCubedDeviations()).isCloseTo(46.44444444444448, within(1e-12));
        assertThat(summary.skewness()).isCloseTo(0.5103681371632725, within(1e-12));
        assertThat(summary.sumFourthDeviations()).isCloseTo(517.7777777777778, within(1e-10));
        assertThat(summary.kurtosis()).isCloseTo(-1.840938581314877, within(1e-12));
        assertThat(summary.sampleStdDev()).isCloseTo(3.011090610836324, within(1e-12));
        assertThat(summary.meanCiLower()).isCloseTo(1.1733875313655244, within(1e-8));
        assertThat(summary.meanCiUpper()).isCloseTo(7.493279135301142, within(1e-8));
        assertThat(summary.modes()).isEmpty();
        assertThat(summary.modeFrequency()).isNull();
        assertThat(summary.modeStatus()).startsWith("Withheld");
    }

    @Test void reportsAllTiedModesWithoutReleasingRareNonModalValues() {
        var values = new ArrayList<Double>();
        values.addAll(Collections.nCopies(6, 0.)); values.addAll(Collections.nCopies(6, 4.)); values.add(99.);
        var summary = calculate(values);
        assertThat(summary.modes()).containsExactly(0., 4.);
        assertThat(summary.modeFrequency()).isEqualTo(6);
        assertThat(summary.modeStatus()).isEqualTo("Available");
    }

    @Test void constantsHaveNoDefinedSkewnessAndAZeroWidthMeanInterval() {
        var summary = calculate(Collections.nCopies(12, 5.));
        assertThat(summary.skewness()).isNull();
        assertThat(summary.kurtosis()).isNull();
        assertThat(summary.sumFourthDeviations()).isZero();
        assertThat(summary.sampleStdDev()).isZero();
        assertThat(summary.sumCubedDeviations()).isZero();
        assertThat(summary.meanCiLower()).isEqualTo(5.);
        assertThat(summary.meanCiUpper()).isEqualTo(5.);
        assertThat(summary.modes()).containsExactly(5.);
        assertThat(summary.modeFrequency()).isEqualTo(12);
    }

    @Test void uniqueValuesHaveNoRepeatedModeAndSmallSamplesKeepUndefinedResultsNull() {
        var summary = calculate(List.of(1., 2., 3., 4., 5., 6.));
        assertThat(summary.modes()).isEmpty();
        assertThat(summary.modeStatus()).isEqualTo("No repeated value");
        assertThat(calculate(List.of(2.)).meanCiLower()).isNull();
        assertThat(calculate(List.of(1., 2.)).skewness()).isNull();
        assertThat(calculate(List.of(1., 2., 3.)).kurtosis()).isNull();
    }

    @Test void largeOffsetsDoNotCancelThirdMoments() {
        var summary = calculate(List.of(1e9 + 1, 1e9 + 2, 1e9 + 3, 1e9 + 4, 1e9 + 8, 1e9 + 8));
        assertThat(summary.skewness()).isCloseTo(0.5103680777781661, within(1e-12));
        assertThat(summary.meanCiLower()).isCloseTo(1000000001.1733875, within(1e-6));
    }

    @Test void boundsModePayloadAndDoesNotSerializeNonFiniteMoments() {
        var values = new ArrayList<Double>();
        for (int value = 0; value <= 200; value++) values.addAll(Collections.nCopies(6, (double) value));
        var summary = calculate(values);
        assertThat(summary.modes()).isEmpty();
        assertThat(summary.modeStatus()).contains("too many tied modes");
        assertThat(calculate(List.of(-1e200, 1e200)).sumCubedDeviations()).isNull();
        assertThat(calculate(List.of(-1e200, 1e200)).sumFourthDeviations()).isNull();
        var unique = new ArrayList<Double>();
        for (int value = 0; value < 1000; value++) unique.add((double) value);
        unique.addAll(Collections.nCopies(6, 2000.));
        assertThat(calculate(unique).modes()).containsExactly(2000.);
        assertThat(calculate(unique).modeFrequency()).isEqualTo(6);
    }
}
