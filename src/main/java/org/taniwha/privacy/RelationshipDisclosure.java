package org.taniwha.privacy;

import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.statistics.CategoricalPairSummary;
import org.taniwha.statistics.NumericPairSummary;

import java.util.*;

final class RelationshipDisclosure {
    private RelationshipDisclosure() {}

    static void numeric(AnalyticsResponseDTO response, List<NumericPairSummary> pairs) {
        Set<List<String>> allowed = new HashSet<>();
        for (var pair : pairs) {
            allowed.add(List.of(pair.featureX(), pair.featureY()));
            allowed.add(List.of(pair.featureY(), pair.featureX()));
        }
        response.setNumericPairSummaries(pairs);
        response.setCovariances(filter(response.getCovariances(), allowed));
        response.setPearsonCorrelations(filter(response.getPearsonCorrelations(), allowed));
        response.setSpearmanCorrelations(filter(response.getSpearmanCorrelations(), allowed));
    }

    static void categorical(AnalyticsResponseDTO response, List<CategoricalPairSummary> pairs) {
        Set<List<String>> allowed = new HashSet<>();
        for (var pair : pairs) {
            allowed.add(List.of(pair.featureX(), pair.featureY()));
            allowed.add(List.of(pair.featureY(), pair.featureX()));
        }
        response.setCategoricalPairSummaries(pairs);
        response.setChiSquareTest(response.getChiSquareTest().stream()
                .filter(test -> allowed.contains(List.of(test.getCategory1(), test.getCategory2()))).toList());
    }

    private static Map<String, Map<String, Double>> filter(Map<String, Map<String, Double>> matrix, Set<List<String>> allowed) {
        Map<String, Map<String, Double>> result = new HashMap<>();
        matrix.forEach((x, values) -> values.forEach((y, value) -> {
            if (allowed.contains(List.of(x, y))) result.computeIfAbsent(x, ignored -> new HashMap<>()).put(y, value);
        }));
        return result;
    }
}
