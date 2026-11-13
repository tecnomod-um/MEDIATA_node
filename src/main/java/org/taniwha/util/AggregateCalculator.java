package org.taniwha.util;

import org.apache.commons.math3.special.Gamma;
import org.apache.commons.math3.stat.correlation.Covariance;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.correlation.SpearmansCorrelation;
import org.apache.commons.math3.stat.inference.ChiSquareTest;
import org.apache.commons.math3.util.Pair;
import org.taniwha.statistics.ChiSquaredTestResult;
import org.taniwha.statistics.NumericPairCount;
import org.taniwha.statistics.NumericPairSummary;
import org.taniwha.statistics.CategoricalPairCount;
import org.taniwha.statistics.CategoricalPairSummary;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.descriptive.moment.Variance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public class AggregateCalculator {
    private static void checkCanceled() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Statistics calculation canceled");
    }


    public List<NumericPairSummary> numericPairSummaries(Map<String, List<Double>> data) {
        List<NumericPairSummary> summaries = new ArrayList<>();
        List<String> names = data.keySet().stream().sorted().toList();
        for (int i = 0; i < names.size(); i++) for (int j = i; j < names.size(); j++) {
            checkCanceled();
            String x = names.get(i), y = names.get(j);
            List<double[]> paired = filterIncompleteData(data.get(x), data.get(y));
            double[] xs = paired.get(0), ys = paired.get(1);
            if (xs.length < minimumPairedCount) continue;
            double meanX = StatUtils.mean(xs), meanY = StatUtils.mean(ys);
            double m2X = new Variance(false).evaluate(xs) * xs.length;
            double m2Y = new Variance(false).evaluate(ys) * xs.length;
            double cross = new Covariance().covariance(xs, ys) * (xs.length - 1);
            if (!Double.isFinite(meanX) || !Double.isFinite(meanY) || !Double.isFinite(m2X)
                    || !Double.isFinite(m2Y) || !Double.isFinite(cross)) continue;
            Map<Pair<Double, Double>, Long> cells = new HashMap<>();
            for (int k = 0; k < xs.length && cells.size() <= AggregateDisclosure.MAX_CELLS; k++) {
                cells.merge(Pair.create(xs[k], ys[k]), 1L, Long::sum);
            }
            List<NumericPairCount> frequencies = List.of();
            if (cells.size() <= AggregateDisclosure.MAX_CELLS
                    && cells.values().stream().allMatch(count -> count >= AggregateDisclosure.MIN_COUNT)) {
                frequencies = cells.entrySet().stream().map(entry -> new NumericPairCount(
                        entry.getKey().getFirst(), entry.getKey().getSecond(), entry.getValue())).toList();
            }
            summaries.add(new NumericPairSummary(x, y, xs.length, meanX, meanY, m2X, m2Y, cross, frequencies));
        }
        return summaries;
    }

    public List<CategoricalPairSummary> categoricalPairSummaries(Map<String, Map<String, Integer>> data,
            Map<Pair<String, String>, Map<Pair<String, String>, Integer>> combinations) {
        return collectCategoricalPairs(data, combinations, AggregateDisclosure.MIN_COUNT);
    }

    private List<CategoricalPairSummary> collectCategoricalPairs(Map<String, Map<String, Integer>> data,
            Map<Pair<String, String>, Map<Pair<String, String>, Integer>> combinations, int minimumCellCount) {
        List<CategoricalPairSummary> summaries = new ArrayList<>();
        List<String> names = data.keySet().stream().sorted().toList();
        for (int i = 0; i < names.size(); i++) for (int j = i + 1; j < names.size(); j++) {
            checkCanceled();
            String x = names.get(i), y = names.get(j);
            Map<Pair<String, String>, Long> cells = new HashMap<>();
            combinations.getOrDefault(Pair.create(x, y), Map.of()).forEach((key, count) -> cells.merge(key, count.longValue(), Long::sum));
            combinations.getOrDefault(Pair.create(y, x), Map.of()).forEach((key, count) -> cells.merge(Pair.create(key.getSecond(), key.getFirst()), count.longValue(), Long::sum));
            long count = cells.values().stream().mapToLong(Long::longValue).sum();
            if (count < minimumPairedCount || cells.size() > AggregateDisclosure.MAX_CELLS
                    || cells.values().stream().anyMatch(n -> n < minimumCellCount)) continue;
            summaries.add(new CategoricalPairSummary(x, y, count, cells.entrySet().stream()
                    .map(entry -> new CategoricalPairCount(entry.getKey().getFirst(), entry.getKey().getSecond(), entry.getValue())).toList()));
        }
        return summaries;
    }

    private final int minimumPairedCount;

    public AggregateCalculator() {
        this(2);
    }

    public AggregateCalculator(int minimumPairedCount) {
        if (minimumPairedCount < 2) throw new IllegalArgumentException("At least two paired records are required");
        this.minimumPairedCount = minimumPairedCount;
    }

    public Map<String, Map<String, Double>> calculateCovariances(Map<String, List<Double>> continuousData) {
        Covariance covarianceCalculator = new Covariance();
        return calculateRelations(continuousData, covarianceCalculator::covariance);
    }

    public Map<String, Map<String, Double>> calculatePearsonCorrelations(Map<String, List<Double>> continuousData) {
        PearsonsCorrelation correlationCalculator = new PearsonsCorrelation();
        return calculateRelations(continuousData, correlationCalculator::correlation);
    }

    public Map<String, Map<String, Double>> calculateSpearmanCorrelations(Map<String, List<Double>> continuousData) {
        SpearmansCorrelation spearmanCorrelationCalculator = new SpearmansCorrelation();
        return calculateRelations(continuousData, spearmanCorrelationCalculator::correlation);
    }

    private Map<String, Map<String, Double>> calculateRelations(
            Map<String, List<Double>> continuousData,
            BiFunction<double[], double[], Double> calculator) {

        Map<String, Map<String, Double>> relations = new HashMap<>();
        List<String> features = new ArrayList<>(continuousData.keySet());

        for (int i = 0; i < features.size(); i++) {
            String feature1 = features.get(i);
            for (int j = i + 1; j < features.size(); j++) {
                checkCanceled();
                String feature2 = features.get(j);

                List<Double> data1 = continuousData.get(feature1);
                List<Double> data2 = continuousData.get(feature2);

                // Filter out rows with missing values and ensure arrays have the same length
                List<double[]> filteredData = filterIncompleteData(data1, data2);
                double[] filteredArray1 = filteredData.get(0);
                double[] filteredArray2 = filteredData.get(1);

                // Perform calculation only if arrays have same length and are non-empty
                if (filteredArray1.length >= minimumPairedCount && filteredArray1.length == filteredArray2.length) {
                    double relation = calculator.apply(filteredArray1, filteredArray2);
                    if (Double.isFinite(relation)) {
                        relations.computeIfAbsent(feature1, k -> new HashMap<>()).put(feature2, relation);
                        relations.computeIfAbsent(feature2, k -> new HashMap<>()).put(feature1, relation);
                    }

                }
            }
        }
        return relations;
    }

    private List<double[]> filterIncompleteData(List<Double> data1, List<Double> data2) {
        List<double[]> filteredData = new ArrayList<>();
        List<Double> filteredData1 = new ArrayList<>();
        List<Double> filteredData2 = new ArrayList<>();

        // Prevent going out of bounds for different data sizes
        int size = Math.min(data1.size(), data2.size());

        for (int i = 0; i < size; i++) {
            if (data1.get(i) != null && data2.get(i) != null
                    && Double.isFinite(data1.get(i)) && Double.isFinite(data2.get(i))) {
                filteredData1.add(data1.get(i));
                filteredData2.add(data2.get(i));
            }
        }

        filteredData.add(filteredData1.stream().mapToDouble(Double::doubleValue).toArray());
        filteredData.add(filteredData2.stream().mapToDouble(Double::doubleValue).toArray());

        return filteredData;
    }

    public List<ChiSquaredTestResult> calculateChiSquaredTest(Map<String, Map<String, Integer>> categoricalData, Map<Pair<String, String>, Map<Pair<String, String>, Integer>> categoryCombinationCounts) {
        List<CategoricalPairSummary> released = collectCategoricalPairs(categoricalData, categoryCombinationCounts,
                minimumPairedCount > 2 ? minimumPairedCount : 1);
        List<ChiSquaredTestResult> results = new ArrayList<>();
        List<String> names = categoricalData.keySet().stream().sorted().toList();
        for (int i = 0; i < names.size(); i++) for (int j = i + 1; j < names.size(); j++) {
            checkCanceled();
            String x = names.get(i), y = names.get(j);
            CategoricalPairSummary summary = released.stream().filter(pair -> pair.featureX().equals(x) && pair.featureY().equals(y)).findFirst().orElse(null);
            double pValue = Double.NaN;
            if (summary != null) {
                List<String> xs = summary.frequencies().stream().map(CategoricalPairCount::x).distinct().sorted().toList();
                List<String> ys = summary.frequencies().stream().map(CategoricalPairCount::y).distinct().sorted().toList();
                if (xs.size() >= 2 && ys.size() >= 2) {
                    long[][] table = new long[xs.size()][ys.size()];
                    summary.frequencies().forEach(cell -> table[xs.indexOf(cell.x())][ys.indexOf(cell.y())] += cell.count());
                    double statistic = new ChiSquareTest().chiSquare(table);
                    double degreesOfFreedom = (xs.size() - 1.0) * (ys.size() - 1.0);
                    // Evaluate the survival probability directly to preserve small p-values.
                    pValue = Gamma.regularizedGammaQ(degreesOfFreedom / 2, statistic / 2);
                }
            }
            results.add(new ChiSquaredTestResult(x, y, pValue));
        }
        return results;
    }
}
