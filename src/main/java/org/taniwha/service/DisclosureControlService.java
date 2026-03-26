package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.statistics.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Applies privacy disclosure-control rules to every {@link AnalyticsResponseDTO}
 * before it is sent to callers.
 *
 * <p>Controls applied:
 * <ol>
 *   <li><b>Global minimum-subset rule</b> – if the record count is below
 *       {@code disclosure.min.subset.size} (default 10), the entire response is
 *       suppressed: all features are moved to {@code omittedFeatures} and all
 *       correlation/covariance matrices are cleared.</li>
 *   <li><b>Per-feature minimum-subset rule</b> – individual features whose own
 *       non-missing count is below the threshold are moved to
 *       {@code omittedFeatures}.</li>
 *   <li><b>Categorical cell suppression (k-anonymity)</b> – category cells with a
 *       frequency count below {@code disclosure.min.cell.count} (default 5) are
 *       removed from the frequency map to avoid singling out individuals.</li>
 *   <li><b>Date histogram suppression</b> – date-bucket entries with a count below
 *       the cell threshold are removed from the date histogram.</li>
 *   <li><b>Outlier-value stripping</b> – raw numeric and date outlier values are
 *       replaced with an empty list so exact boundary observations cannot be
 *       used to re-identify individuals.</li>
 *   <li><b>Correlation suppression</b> – covariance, Pearson/Spearman correlation,
 *       and chi-squared matrices are cleared when the record count is below the
 *       minimum-subset threshold.</li>
 * </ol>
 *
 * <p>All suppression events are logged at WARN level so that the audit trail in
 * application logs captures every disclosure decision.
 */
@Service
public class DisclosureControlService {

    private static final Logger logger = LoggerFactory.getLogger(DisclosureControlService.class);

    private final int minSubsetSize;
    private final int minCellCount;

    public DisclosureControlService(
            @Value("${disclosure.min.subset.size:10}") int minSubsetSize,
            @Value("${disclosure.min.cell.count:5}") int minCellCount) {
        this.minSubsetSize = minSubsetSize;
        this.minCellCount = minCellCount;
    }

    public int getMinSubsetSize() {
        return minSubsetSize;
    }

    public int getMinCellCount() {
        return minCellCount;
    }

    /**
     * Applies all disclosure-control rules to {@code response} in place.
     *
     * @param response     the analytics response to sanitise
     * @param totalRecords total number of records that were processed
     * @return the number of features that were fully suppressed
     */
    public int apply(AnalyticsResponseDTO response, long totalRecords) {
        int suppressed = 0;

        if (totalRecords < minSubsetSize) {
            suppressed += suppressAll(response, totalRecords);
            return suppressed;
        }

        suppressed += suppressSmallContinuousFeatures(response);
        suppressed += suppressSmallCategoricalCells(response);
        suppressed += suppressSmallDateFeatures(response);
        stripContinuousOutliers(response);
        stripDateOutliers(response);

        return suppressed;
    }

    // -------------------------------------------------------------------------
    // Global suppression
    // -------------------------------------------------------------------------

    private int suppressAll(AnalyticsResponseDTO response, long totalRecords) {
        String reason = String.format(
                "Subset too small – minimum %d records required, only %d present", minSubsetSize, totalRecords);

        List<OmittedFeatureStatistics> omitted = new ArrayList<>(
                safeList(response.getOmittedFeatures()));
        int count = 0;

        count += moveFeaturesToOmitted(response.getContinuousFeatures(), omitted, reason);
        count += moveFeaturesToOmitted(response.getCategoricalFeatures(), omitted, reason);
        count += moveDateFeaturesToOmitted(response.getDateFeatures(), omitted, reason);

        response.setContinuousFeatures(List.of());
        response.setCategoricalFeatures(List.of());
        response.setDateFeatures(List.of());
        response.setOmittedFeatures(omitted);

        clearCorrelations(response);

        logger.warn("Full disclosure suppression applied: {} feature(s) suppressed (record count {} < minimum {})",
                count, totalRecords, minSubsetSize);
        return count;
    }

    private int moveFeaturesToOmitted(List<FeatureStatistics> features,
                                      List<OmittedFeatureStatistics> omitted,
                                      String reason) {
        if (features == null) return 0;
        for (FeatureStatistics fs : features) {
            omitted.add(new OmittedFeatureStatistics(
                    fs.getFeatureName(), fs.getCount(),
                    fs.getPercentMissing(), fs.getMissingValuesCount(), reason));
        }
        return features.size();
    }

    private int moveDateFeaturesToOmitted(List<DateFeatureStatistics> features,
                                          List<OmittedFeatureStatistics> omitted,
                                          String reason) {
        if (features == null) return 0;
        for (DateFeatureStatistics fs : features) {
            omitted.add(new OmittedFeatureStatistics(
                    fs.getFeatureName(), fs.getCount(),
                    fs.getPercentMissing(), fs.getMissingValuesCount(), reason));
        }
        return features.size();
    }

    // -------------------------------------------------------------------------
    // Per-feature minimum-subset suppression
    // -------------------------------------------------------------------------

    private int suppressSmallContinuousFeatures(AnalyticsResponseDTO response) {
        if (response.getContinuousFeatures() == null) return 0;

        String reason = String.format(
                "Feature record count below minimum subset size (%d)", minSubsetSize);
        List<FeatureStatistics> kept = new ArrayList<>();
        List<OmittedFeatureStatistics> omitted = new ArrayList<>(safeList(response.getOmittedFeatures()));
        int count = 0;

        for (FeatureStatistics fs : response.getContinuousFeatures()) {
            if (fs.getCount() < minSubsetSize) {
                omitted.add(new OmittedFeatureStatistics(
                        fs.getFeatureName(), fs.getCount(),
                        fs.getPercentMissing(), fs.getMissingValuesCount(), reason));
                count++;
                logger.warn("Suppressing continuous feature '{}': count {} below minimum {}",
                        fs.getFeatureName(), fs.getCount(), minSubsetSize);
            } else {
                kept.add(fs);
            }
        }

        response.setContinuousFeatures(kept);
        response.setOmittedFeatures(omitted);
        return count;
    }

    private int suppressSmallDateFeatures(AnalyticsResponseDTO response) {
        if (response.getDateFeatures() == null) return 0;

        String reason = String.format(
                "Feature record count below minimum subset size (%d)", minSubsetSize);
        List<DateFeatureStatistics> kept = new ArrayList<>();
        List<OmittedFeatureStatistics> omitted = new ArrayList<>(safeList(response.getOmittedFeatures()));
        int count = 0;

        for (DateFeatureStatistics fs : response.getDateFeatures()) {
            if (fs.getCount() < minSubsetSize) {
                omitted.add(new OmittedFeatureStatistics(
                        fs.getFeatureName(), fs.getCount(),
                        fs.getPercentMissing(), fs.getMissingValuesCount(), reason));
                count++;
                logger.warn("Suppressing date feature '{}': count {} below minimum {}",
                        fs.getFeatureName(), fs.getCount(), minSubsetSize);
            } else {
                suppressSmallDateHistogramCells(fs);
                kept.add(fs);
            }
        }

        response.setDateFeatures(kept);
        response.setOmittedFeatures(omitted);
        return count;
    }

    // -------------------------------------------------------------------------
    // Categorical cell suppression
    // -------------------------------------------------------------------------

    private int suppressSmallCategoricalCells(AnalyticsResponseDTO response) {
        if (response.getCategoricalFeatures() == null) return 0;

        String suppressedReason = String.format(
                "All categories below minimum cell count (%d)", minCellCount);
        List<FeatureStatistics> kept = new ArrayList<>();
        List<OmittedFeatureStatistics> omitted = new ArrayList<>(safeList(response.getOmittedFeatures()));
        int suppressedFeatures = 0;

        for (FeatureStatistics fs : response.getCategoricalFeatures()) {
            if (!(fs instanceof CategoricalFeatureStatistics cfs)) {
                kept.add(fs);
                continue;
            }

            Map<String, Integer> counts = cfs.getCategoryCounts();
            int before = counts.size();
            counts.entrySet().removeIf(e -> e.getValue() < minCellCount);
            int removed = before - counts.size();

            if (removed > 0) {
                logger.warn("Suppressed {} small cell(s) in categorical feature '{}' (threshold {})",
                        removed, cfs.getFeatureName(), minCellCount);
            }

            if (counts.isEmpty()) {
                omitted.add(new OmittedFeatureStatistics(
                        cfs.getFeatureName(), cfs.getCount(),
                        cfs.getPercentMissing(), cfs.getMissingValuesCount(), suppressedReason));
                suppressedFeatures++;
                logger.warn("Suppressing categorical feature '{}': all cells below minimum count {}",
                        cfs.getFeatureName(), minCellCount);
            } else if (removed > 0) {
                kept.add(rebuildCategoricalFeature(cfs, counts));
            } else {
                kept.add(cfs);
            }
        }

        response.setCategoricalFeatures(kept);
        response.setOmittedFeatures(omitted);
        return suppressedFeatures;
    }

    /**
     * Rebuilds a {@link CategoricalFeatureStatistics} after cells have been
     * removed from its frequency map so that derived fields (mode, cardinality)
     * remain consistent.
     */
    private CategoricalFeatureStatistics rebuildCategoricalFeature(CategoricalFeatureStatistics original,
                                                                   Map<String, Integer> filteredCounts) {
        List<Map.Entry<String, Integer>> sorted = filteredCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .collect(Collectors.toList());

        Map.Entry<String, Integer> modeEntry = sorted.get(0);
        String mode = modeEntry.getKey();
        int modeFreq = modeEntry.getValue();
        double modePercent = (double) modeFreq / original.getCount() * 100;

        String secondMode = sorted.size() > 1 ? sorted.get(1).getKey() : null;
        Integer secondModeFreq = secondMode != null ? filteredCounts.get(secondMode) : null;
        Double secondModePercent = secondModeFreq != null
                ? (double) secondModeFreq / original.getCount() * 100 : null;

        return new CategoricalFeatureStatistics(
                original.getFeatureName(), original.getCount(),
                original.getPercentMissing(), original.getMissingValuesCount(),
                filteredCounts.size(),
                mode, modeFreq, modePercent,
                secondMode, secondModeFreq, secondModePercent,
                filteredCounts);
    }

    // -------------------------------------------------------------------------
    // Date histogram cell suppression
    // -------------------------------------------------------------------------

    private void suppressSmallDateHistogramCells(DateFeatureStatistics fs) {
        Map<String, Long> histogram = fs.getDateHistogram();
        if (histogram == null) return;
        int before = histogram.size();
        histogram.entrySet().removeIf(e -> e.getValue() < minCellCount);
        int removed = before - histogram.size();
        if (removed > 0) {
            logger.warn("Suppressed {} small date histogram bucket(s) in feature '{}' (threshold {})",
                    removed, fs.getFeatureName(), minCellCount);
        }
    }

    // -------------------------------------------------------------------------
    // Outlier-value stripping
    // -------------------------------------------------------------------------

    /**
     * Replaces raw numeric outlier lists with an empty list to prevent
     * re-identification via extreme individual values.
     */
    private void stripContinuousOutliers(AnalyticsResponseDTO response) {
        if (response.getContinuousFeatures() == null) return;

        List<FeatureStatistics> sanitised = new ArrayList<>();
        for (FeatureStatistics fs : response.getContinuousFeatures()) {
            if (fs instanceof ContinuousFeatureStatistics cfs && !cfs.getOutliers().isEmpty()) {
                sanitised.add(new ContinuousFeatureStatistics(
                        cfs.getFeatureName(), cfs.getCount(),
                        cfs.getPercentMissing(), cfs.getMissingValuesCount(),
                        cfs.getCardinality(),
                        cfs.getMin(), cfs.getMax(), cfs.getMean(), cfs.getStdDev(),
                        cfs.getQrt1(), cfs.getMedian(), cfs.getQrt3(),
                        cfs.getHistogram(), cfs.getBinRanges(),
                        List.of()));
            } else {
                sanitised.add(fs);
            }
        }
        response.setContinuousFeatures(sanitised);
    }

    /**
     * Replaces raw date outlier lists with an empty list to prevent
     * re-identification via rare boundary dates.
     */
    private void stripDateOutliers(AnalyticsResponseDTO response) {
        if (response.getDateFeatures() == null) return;

        List<DateFeatureStatistics> sanitised = new ArrayList<>();
        for (DateFeatureStatistics fs : response.getDateFeatures()) {
            if (!fs.getOutliers().isEmpty()) {
                sanitised.add(new DateFeatureStatistics(
                        fs.getFeatureName(), fs.getCount(),
                        fs.getPercentMissing(), fs.getMissingValuesCount(),
                        fs.getEarliestDate(), fs.getLatestDate(),
                        fs.getDateHistogram(),
                        List.of(),
                        fs.getMean(), fs.getStdDev(),
                        fs.getMedian(), fs.getQ1(), fs.getQ3()));
            } else {
                sanitised.add(fs);
            }
        }
        response.setDateFeatures(sanitised);
    }

    // -------------------------------------------------------------------------
    // Correlation suppression
    // -------------------------------------------------------------------------

    private void clearCorrelations(AnalyticsResponseDTO response) {
        response.setCovariances(Map.of());
        response.setPearsonCorrelations(Map.of());
        response.setSpearmanCorrelations(Map.of());
        response.setChiSquareTest(List.of());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private <T> List<T> safeList(List<T> list) {
        return list != null ? list : List.of();
    }
}
