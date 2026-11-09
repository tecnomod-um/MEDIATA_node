package org.taniwha.privacy;

import org.springframework.stereotype.Service;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.statistics.*;
import java.nio.file.Path;
import java.util.*;
import org.taniwha.util.AggregateDisclosure;

/** Releases descriptive aggregates; the ledger separately gates requested query cohorts. */
@Service
public class DiscoveryDisclosureService {
    private final DiscoveryQueryLedger ledger;
    public DiscoveryDisclosureService(DiscoveryQueryLedger ledger) { this.ledger = ledger; }
    public DisclosureObservation begin(Path path) { return new DisclosureObservation(ledger.begin(path)); }
    public AnalyticsResponseDTO release(DisclosureObservation observation, AnalyticsResponseDTO response) {
        return ledger.release(observation.version, observation.rows, observation.selected, partition -> {
            AnalyticsResponseDTO permitted = redact(response, observation);
            permitted.setDisclosurePolicyVersion(1);
            return permitted;
        });
    }

    private AnalyticsResponseDTO redact(AnalyticsResponseDTO response, DisclosureObservation observation) {
        Set<String> numeric = new HashSet<>(), categorical = new HashSet<>();
        List<OmittedFeatureStatistics> omitted = new ArrayList<>(response.getOmittedFeatures());
        List<FeatureStatistics> continuous = new ArrayList<>(), categories = new ArrayList<>();
        List<DateFeatureStatistics> dates = new ArrayList<>();
        for (FeatureStatistics feature : response.getContinuousFeatures()) {
            BitSet cohort = observation.members(feature.getFeatureName(), true);
            if (!valid(cohort, feature.getCount())) {
                omit(omitted, feature);
                continue;
            }
            numeric.add(feature.getFeatureName());
            ContinuousFeatureStatistics item = (ContinuousFeatureStatistics) feature;
            HistogramDisclosure.release(item);
            continuous.add(item);
        }
        for (FeatureStatistics feature : response.getCategoricalFeatures()) {
            CategoricalFeatureStatistics item = (CategoricalFeatureStatistics) feature;
            if (!valid(observation.members(feature.getFeatureName(), false), feature.getCount())) { omit(omitted, feature); continue; }
            categories.add(item); categorical.add(feature.getFeatureName());
        }
        for (DateFeatureStatistics feature : response.getDateFeatures()) {
            if (!valid(observation.members(feature.getFeatureName(), true), feature.getCount())) { omit(omitted, feature); continue; }
            dates.add(feature); numeric.add(feature.getFeatureName());
        }
        response.setContinuousFeatures(continuous); response.setCategoricalFeatures(categories); response.setDateFeatures(dates);
        List<NumericPairSummary> pairs = new ArrayList<>(); Set<List<String>> numericPairs = new HashSet<>();
        for (NumericPairSummary pair : response.getNumericPairSummaries()) {
            if (!numeric.contains(pair.featureX()) || !numeric.contains(pair.featureY())) continue;
            BitSet cohort = DisclosureObservation.intersect(observation.members(pair.featureX(), true), observation.members(pair.featureY(), true));
            if (!valid(cohort, pair.count())) continue;
            numericPairs.add(List.of(pair.featureX(), pair.featureY())); numericPairs.add(List.of(pair.featureY(), pair.featureX()));
            pairs.add(pair);
        }
        response.setNumericPairSummaries(pairs);
        response.setCovariances(filterMatrix(response.getCovariances(), numericPairs));
        response.setPearsonCorrelations(filterMatrix(response.getPearsonCorrelations(), numericPairs));
        response.setSpearmanCorrelations(filterMatrix(response.getSpearmanCorrelations(), numericPairs));
        List<CategoricalPairSummary> tables = new ArrayList<>(); Set<List<String>> categoryPairs = new HashSet<>();
        for (CategoricalPairSummary pair : response.getCategoricalPairSummaries()) {
            if (!categorical.contains(pair.featureX()) || !categorical.contains(pair.featureY())) continue;
            BitSet cohort = DisclosureObservation.intersect(observation.members(pair.featureX(), false), observation.members(pair.featureY(), false));
            if (!valid(cohort, pair.count())) continue;
            tables.add(pair); categoryPairs.add(List.of(pair.featureX(), pair.featureY())); categoryPairs.add(List.of(pair.featureY(), pair.featureX()));
        }
        response.setCategoricalPairSummaries(tables);
        response.setChiSquareTest(response.getChiSquareTest().stream().filter(test -> categoryPairs.contains(List.of(test.getCategory1(), test.getCategory2()))).toList());
        // Omission metadata must not bypass count suppression, including high-cardinality omissions.
        response.setOmittedFeatures(omitted.stream().map(feature -> new OmittedFeatureStatistics(feature.getFeatureName(), 0, 0, 0,
                feature.getReason().startsWith("Suppressed for privacy:") ? feature.getReason() : "Suppressed for privacy: release controls")).toList());
        return response;
    }
    private boolean valid(BitSet cohort, long count) {
        return cohort != null && count >= AggregateDisclosure.MIN_COUNT && cohort.cardinality() == count;
    }
    private void omit(List<OmittedFeatureStatistics> omitted, FeatureStatistics feature) {
        omitted.add(new OmittedFeatureStatistics(feature.getFeatureName(), 0, 0, 0, "Suppressed for privacy: release controls"));
    }
    private Map<String, Map<String, Double>> filterMatrix(Map<String, Map<String, Double>> matrix, Set<List<String>> allowed) {
        Map<String, Map<String, Double>> result = new HashMap<>();
        matrix.forEach((x, values) -> values.forEach((y, value) -> {
            if (allowed.contains(List.of(x, y))) result.computeIfAbsent(x, ignored -> new HashMap<>()).put(y, value);
        }));
        return result;
    }
}
