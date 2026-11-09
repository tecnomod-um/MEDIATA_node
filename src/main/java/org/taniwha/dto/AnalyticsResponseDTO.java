package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;
import org.taniwha.statistics.ChiSquaredTestResult;
import org.taniwha.statistics.DateFeatureStatistics;
import org.taniwha.statistics.FeatureStatistics;
import org.taniwha.statistics.OmittedFeatureStatistics;
import org.taniwha.statistics.NumericPairSummary;
import org.taniwha.statistics.CategoricalPairSummary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Setter
@Getter
public class AnalyticsResponseDTO {
    private String message;
    private String fileName;
    private List<FeatureStatistics> continuousFeatures;
    private List<FeatureStatistics> categoricalFeatures;
    private List<DateFeatureStatistics> dateFeatures;
    private List<OmittedFeatureStatistics> omittedFeatures;

    private Map<String, Map<String, Double>> covariances;
    private Map<String, Map<String, Double>> pearsonCorrelations;
    private Map<String, Map<String, Double>> spearmanCorrelations;
    private List<ChiSquaredTestResult> chiSquareTest;
    private final int relationshipSummaryVersion = 1;
    // Population SD, R7/linear quantiles, category percentages over observed values.
    private final int statisticsConventionVersion = 1;
    private int disclosurePolicyVersion;
    private int featureTypeSummaryVersion;
    private Map<String, String> featureTypes = new HashMap<>();
    private List<NumericPairSummary> numericPairSummaries = new ArrayList<>();
    private List<CategoricalPairSummary> categoricalPairSummaries = new ArrayList<>();

    public AnalyticsResponseDTO() {
        this.continuousFeatures = new ArrayList<>();
        this.categoricalFeatures = new ArrayList<>();
        this.dateFeatures = new ArrayList<>();
        this.omittedFeatures = new ArrayList<>();

        this.covariances = new HashMap<>();
        this.pearsonCorrelations = new HashMap<>();
        this.spearmanCorrelations = new HashMap<>();
        this.chiSquareTest = new ArrayList<>();
    }

    public AnalyticsResponseDTO(String message) {
        this();
        this.message = message;
    }
}
