package org.taniwha.privacy;

import org.springframework.stereotype.Service;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.statistics.*;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Enforces release-history rules against the exact cohorts used by response fields. */
@Service
public class DiscoveryDisclosureService {
    private final DiscoveryQueryLedger ledger;
    public DiscoveryDisclosureService(DiscoveryQueryLedger ledger) { this.ledger = ledger; }
    public DisclosureObservation begin(Path path) { return new DisclosureObservation(ledger.begin(path)); }
    public AnalyticsResponseDTO release(DisclosureObservation observation, AnalyticsResponseDTO response) {
        return ledger.release(observation.version, observation.rows, observation.selected, partition -> {
            AnalyticsResponseDTO permitted = redact(response, observation, partition);
            permitted.setDisclosurePolicyVersion(1);
            return permitted;
        });
    }

    private AnalyticsResponseDTO redact(AnalyticsResponseDTO response, DisclosureObservation observation, CohortPartition partition) {
        Set<String> numeric = new HashSet<>(), categorical = new HashSet<>();
        List<OmittedFeatureStatistics> omitted = new ArrayList<>(response.getOmittedFeatures());
        List<FeatureStatistics> continuous = new ArrayList<>(), categories = new ArrayList<>();
        List<DateFeatureStatistics> dates = new ArrayList<>();
        for (FeatureStatistics feature : response.getContinuousFeatures()) {
            BitSet cohort = observation.members(feature.getFeatureName(), true);
            List<BitSet> implied = new ArrayList<>(observation.impliedMomentCells(feature.getFeatureName()));
            implied.add(cohort);
            if (!valid(cohort, feature.getCount()) || !partition.allow(implied)) {
                omit(omitted, feature);
                continue;
            }
            numeric.add(feature.getFeatureName());
            ContinuousFeatureStatistics item = (ContinuousFeatureStatistics) feature;
            List<BitSet> cells = item.getValueCounts().stream().map(cell -> observation.value(feature.getFeatureName(), cell.value(), true)).toList();
            if (!partition.allow(cells)) item.setValueCounts(List.of());
            List<BitSet> bins = observation.histogram(item);
            if (bins == null || !partition.allow(bins)) item.suppressHistogram();
            continuous.add(item);
        }
        for (FeatureStatistics feature : response.getCategoricalFeatures()) {
            CategoricalFeatureStatistics item = (CategoricalFeatureStatistics) feature;
            List<BitSet> cohorts = new ArrayList<>(); cohorts.add(observation.members(feature.getFeatureName(), false));
            item.getCategoryCounts().keySet().forEach(value -> cohorts.add(observation.value(feature.getFeatureName(), value, false)));
            if (!valid(cohorts.get(0), feature.getCount()) || !partition.allow(cohorts)) { omit(omitted, feature); continue; }
            categories.add(item); categorical.add(feature.getFeatureName());
        }
        for (DateFeatureStatistics feature : response.getDateFeatures()) {
            List<BitSet> cohorts = new ArrayList<>(); cohorts.add(observation.members(feature.getFeatureName(), true));
            feature.getDateHistogram().keySet().forEach(day -> cohorts.add(observation.value(feature.getFeatureName(), (double) LocalDate.parse(day).toEpochDay(), true)));
            if (!valid(cohorts.get(0), feature.getCount()) || !partition.allow(cohorts)) { omit(omitted, feature); continue; }
            dates.add(feature); numeric.add(feature.getFeatureName());
        }
        response.setContinuousFeatures(continuous); response.setCategoricalFeatures(categories); response.setDateFeatures(dates);
        List<NumericPairSummary> pairs = new ArrayList<>(); Set<List<String>> numericPairs = new HashSet<>();
        for (NumericPairSummary pair : response.getNumericPairSummaries()) {
            if (!numeric.contains(pair.featureX()) || !numeric.contains(pair.featureY())) continue;
            BitSet cohort = DisclosureObservation.intersect(observation.members(pair.featureX(), true), observation.members(pair.featureY(), true));
            List<BitSet> implied = new ArrayList<>(observation.impliedMomentCells(pair.featureX(), pair.featureY()));
            implied.add(cohort);
            if (!valid(cohort, pair.count()) || !partition.allow(implied)) continue;
            numericPairs.add(List.of(pair.featureX(), pair.featureY())); numericPairs.add(List.of(pair.featureY(), pair.featureX()));
            List<BitSet> cells = pair.frequencies().stream().map(cell -> DisclosureObservation.intersect(
                    observation.value(pair.featureX(), cell.x(), true), observation.value(pair.featureY(), cell.y(), true))).toList();
            NumericPairSummary releasedPair = pair;
            if (!partition.allow(cells)) releasedPair = new NumericPairSummary(pair.featureX(), pair.featureY(), pair.count(), pair.meanX(), pair.meanY(), pair.m2X(), pair.m2Y(), pair.crossMoment(), List.of());
            pairs.add(releasedPair);
        }
        response.setNumericPairSummaries(pairs);
        response.setCovariances(filterMatrix(response.getCovariances(), numericPairs));
        response.setPearsonCorrelations(filterMatrix(response.getPearsonCorrelations(), numericPairs));
        response.setSpearmanCorrelations(filterMatrix(response.getSpearmanCorrelations(), numericPairs));
        List<CategoricalPairSummary> tables = new ArrayList<>(); Set<List<String>> categoryPairs = new HashSet<>();
        for (CategoricalPairSummary pair : response.getCategoricalPairSummaries()) {
            if (!categorical.contains(pair.featureX()) || !categorical.contains(pair.featureY())) continue;
            List<BitSet> cells = pair.frequencies().stream().map(cell -> DisclosureObservation.intersect(
                    observation.value(pair.featureX(), cell.x(), false), observation.value(pair.featureY(), cell.y(), false))).toList();
            BitSet cohort = DisclosureObservation.intersect(observation.members(pair.featureX(), false), observation.members(pair.featureY(), false));
            List<BitSet> cohorts = new ArrayList<>(cells); cohorts.add(cohort);
            if (!valid(cohort, pair.count()) || !partition.allow(cohorts)) continue;
            tables.add(pair); categoryPairs.add(List.of(pair.featureX(), pair.featureY())); categoryPairs.add(List.of(pair.featureY(), pair.featureX()));
        }
        response.setCategoricalPairSummaries(tables);
        response.setChiSquareTest(response.getChiSquareTest().stream().filter(test -> categoryPairs.contains(List.of(test.getCategory1(), test.getCategory2()))).toList());
        // Omission metadata must not bypass count suppression, including high-cardinality omissions.
        response.setOmittedFeatures(omitted.stream().map(feature -> new OmittedFeatureStatistics(feature.getFeatureName(), 0, 0, 0,
                feature.getReason().startsWith("Suppressed for privacy:") ? feature.getReason() : "Suppressed for privacy: release controls")).toList());
        return response;
    }
    private boolean valid(BitSet cohort, long count) { return cohort != null && cohort.cardinality() == count; }
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
