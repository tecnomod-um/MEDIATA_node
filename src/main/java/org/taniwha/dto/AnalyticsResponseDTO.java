package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;
import org.taniwha.statistics.ChiSquaredTestResult;
import org.taniwha.statistics.DateFeatureStatistics;
import org.taniwha.statistics.FeatureStatistics;
import org.taniwha.statistics.OmittedFeatureStatistics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Setter
@Getter
public class AnalyticsResponseDTO {
    private String message;

    private List<FeatureStatistics> continuousFeatures;
    private List<FeatureStatistics> categoricalFeatures;
    private List<DateFeatureStatistics> dateFeatures;
    private List<OmittedFeatureStatistics> omittedFeatures;

    private Map<String, Map<String, Double>> covariances;
    private Map<String, Map<String, Double>> pearsonCorrelations;
    private Map<String, Map<String, Double>> spearmanCorrelations;
    private List<ChiSquaredTestResult> chiSquareTest;

    public AnalyticsResponseDTO() {
        this.continuousFeatures = new ArrayList<>();
        this.categoricalFeatures = new ArrayList<>();
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
