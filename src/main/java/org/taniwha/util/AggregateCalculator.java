package org.taniwha.util;

import org.apache.commons.math3.stat.correlation.Covariance;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.correlation.SpearmansCorrelation;
import org.apache.commons.math3.stat.inference.ChiSquareTest;
import org.apache.commons.math3.util.Pair;
import org.taniwha.statistics.ChiSquaredTestResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public class AggregateCalculator {

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
                String feature2 = features.get(j);

                List<Double> data1 = continuousData.get(feature1);
                List<Double> data2 = continuousData.get(feature2);

                // Filter out rows with missing values and ensure arrays have the same length
                List<double[]> filteredData = filterIncompleteData(data1, data2);
                double[] filteredArray1 = filteredData.get(0);
                double[] filteredArray2 = filteredData.get(1);

                // Perform calculation only if arrays have same length and are non-empty
                if (filteredArray1.length > 0 && filteredArray1.length == filteredArray2.length) {
                    double relation = calculator.apply(filteredArray1, filteredArray2);
                    if (!Double.isNaN(relation)) {
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
            if (data1.get(i) != null && data2.get(i) != null) {
                filteredData1.add(data1.get(i));
                filteredData2.add(data2.get(i));
            }
        }

        filteredData.add(filteredData1.stream().mapToDouble(Double::doubleValue).toArray());
        filteredData.add(filteredData2.stream().mapToDouble(Double::doubleValue).toArray());

        return filteredData;
    }

    public List<ChiSquaredTestResult> calculateChiSquaredTest(Map<String, Map<String, Integer>> categoricalData, Map<Pair<String, String>, Map<Pair<String, String>, Integer>> categoryCombinationCounts) {
        ChiSquareTest chiSquareTest = new ChiSquareTest();
        List<ChiSquaredTestResult> chiSquaredResults = new ArrayList<>();
        List<String> categories = new ArrayList<>(categoricalData.keySet());

        for (int i = 0; i < categories.size(); i++) {
            for (int j = i + 1; j < categories.size(); j++) {
                String category1 = categories.get(i);
                String category2 = categories.get(j);
                Pair<String, String> categoryPair = new Pair<>(category1, category2);
                Map<Pair<String, String>, Integer> combinations = categoryCombinationCounts.get(categoryPair);

                if (combinations == null || combinations.isEmpty()) {
                    chiSquaredResults.add(new ChiSquaredTestResult(category1, category2, Double.NaN));
                    continue;
                }

                long[][] table = constructContingencyTable(combinations, categoricalData.get(category1), categoricalData.get(category2));
                if (table.length < 2 || table[0].length < 2) {
                    chiSquaredResults.add(new ChiSquaredTestResult(category1, category2, Double.NaN));
                    continue;
                }

                double pValue = chiSquareTest.chiSquareTest(table);
                chiSquaredResults.add(new ChiSquaredTestResult(category1, category2, pValue));
            }
        }
        return chiSquaredResults;
    }

    private long[][] constructContingencyTable(Map<Pair<String, String>, Integer> combinations, Map<String, Integer> categories1, Map<String, Integer> categories2) {
        List<String> uniqueValues1 = new ArrayList<>(categories1.keySet());
        List<String> uniqueValues2 = new ArrayList<>(categories2.keySet());

        // Ensure both categories have more than one unique value to avoid dimension mismatch
        if (uniqueValues1.size() < 2 || uniqueValues2.size() < 2)
            return new long[0][];

        long[][] table = new long[uniqueValues1.size()][uniqueValues2.size()];

        for (int i = 0; i < uniqueValues1.size(); i++) {
            for (int j = 0; j < uniqueValues2.size(); j++) {
                Pair<String, String> combination = new Pair<>(uniqueValues1.get(i), uniqueValues2.get(j));
                Integer count = combinations.get(combination);
                table[i][j] = count != null ? count : 0;
            }
        }

        return table;
    }
}
