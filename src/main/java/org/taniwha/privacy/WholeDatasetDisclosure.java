package org.taniwha.privacy;

import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.statistics.*;
import org.taniwha.util.AggregateDisclosure;

import java.util.*;

// Retain missingness without publishing small-cohort values or rare exact distributions.
final class WholeDatasetDisclosure {
    private WholeDatasetDisclosure() {}

    static AnalyticsResponseDTO release(AnalyticsResponseDTO response) {
        List<OmittedFeatureStatistics> omitted = new ArrayList<>(response.getOmittedFeatures());
        Map<String, Integer> numeric = new HashMap<>();
        Set<String> categorical = new HashSet<>();
        response.setContinuousFeatures(response.getContinuousFeatures().stream().filter(feature -> {
            var item = (ContinuousFeatureStatistics) feature;
            boolean permitted = feature.getCount() == 0 || feature.getCount() >= AggregateDisclosure.MIN_COUNT
                    && (item.getCardinality() != 2 || !item.getValueCounts().isEmpty());
            if (!permitted) {
                omit(omitted, feature, "continuous");
                return false;
            }
            numeric.put(feature.getFeatureName(), item.getCardinality());
            if (feature.getCount() > 0) HistogramDisclosure.release(item);
            return true;
        }).toList());
        response.setCategoricalFeatures(response.getCategoricalFeatures().stream().filter(feature -> {
            var item = (CategoricalFeatureStatistics) feature;
            if (feature.getCount() > 0 && (feature.getCount() < AggregateDisclosure.MIN_COUNT
                    || item.getCategoryCounts().values().stream().anyMatch(count -> count < AggregateDisclosure.MIN_COUNT))) {
                omit(omitted, feature, "categorical");
                return false;
            }
            categorical.add(feature.getFeatureName());
            return true;
        }).toList());
        response.setDateFeatures(response.getDateFeatures().stream().filter(feature -> {
            if (feature.getCount() < AggregateDisclosure.MIN_COUNT
                    || feature.getDateHistogram().values().stream().anyMatch(count -> count < AggregateDisclosure.MIN_COUNT)) {
                omit(omitted, feature, "date");
                return false;
            }
            numeric.put(feature.getFeatureName(), feature.getDateHistogram().size());
            return true;
        }).toList());
        response.setOmittedFeatures(omitted);
        RelationshipDisclosure.numeric(response, response.getNumericPairSummaries().stream()
                .filter(pair -> numeric.containsKey(pair.featureX()) && numeric.containsKey(pair.featureY()))
                .filter(pair -> pair.count() >= AggregateDisclosure.MIN_COUNT)
                .filter(pair -> numeric.get(pair.featureX()) != 2 || numeric.get(pair.featureY()) != 2 || !pair.frequencies().isEmpty())
                .toList());
        RelationshipDisclosure.categorical(response, response.getCategoricalPairSummaries().stream()
                .filter(pair -> categorical.contains(pair.featureX()) && categorical.contains(pair.featureY()))
                .filter(pair -> pair.count() >= AggregateDisclosure.MIN_COUNT)
                .filter(pair -> pair.frequencies().stream().allMatch(cell -> cell.count() >= AggregateDisclosure.MIN_COUNT))
                .toList());
        response.setDisclosurePolicyVersion(3);
        response.setDisclosureMode("WHOLE_DATASET");
        return response;
    }

    private static void omit(List<OmittedFeatureStatistics> omitted, FeatureStatistics feature, String type) {
        String reason = feature.getCount() < AggregateDisclosure.MIN_COUNT
                ? "Suppressed for privacy: fewer than six observations"
                : "Suppressed for privacy: rare exact values";
        omitted.add(new OmittedFeatureStatistics(feature.getFeatureName(), feature.getCount(), feature.getPercentMissing(),
                feature.getMissingValuesCount(), reason, type, true));
    }
}
