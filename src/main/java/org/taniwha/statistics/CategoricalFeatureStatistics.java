package org.taniwha.statistics;

import lombok.Getter;

import java.util.Map;
import java.util.List;
import java.util.Set;

@Getter
public class CategoricalFeatureStatistics extends FeatureStatistics {
    private final int cardinality;
    private String mode;
    private Integer modeFrequency;
    private Double modeFrequencyPercentage;
    private String secondMode;
    private Integer secondModeFrequency;
    private Double secondModePercentage;
    private Map<String, Integer> categoryCounts;
    private List<CategoryHistogramBin> categoryBins = List.of();
    private String categoryCountsStatus = "AVAILABLE";

    public void coarsenDistribution(List<CategoryHistogramBin> bins, Set<String> publishedCounts, int minimumCount) {
        if (modeFrequency < minimumCount) mode = null;
        if (mode == null || !publishedCounts.contains(mode)) {
            modeFrequency = null;
            modeFrequencyPercentage = null;
        }
        if (secondModeFrequency != null && secondModeFrequency < minimumCount) secondMode = null;
        if (secondMode == null || !publishedCounts.contains(secondMode)) {
            secondModeFrequency = null;
            secondModePercentage = null;
        }
        categoryCounts = Map.of();
        categoryBins = List.copyOf(bins);
        categoryCountsStatus = "COARSENED";
    }

    public CategoricalFeatureStatistics(
            String featureName, long count, double percentMissing, long missingValuesCount,
            int cardinality, String mode, int modeFrequency, double modeFrequencyPercentage,
            String secondMode, Integer secondModeFrequency, Double secondModePercentage,
            Map<String, Integer> categoryCounts) {
        super(featureName, count, percentMissing, missingValuesCount);
        this.cardinality = cardinality;
        this.mode = mode;
        this.modeFrequency = modeFrequency;
        this.modeFrequencyPercentage = modeFrequencyPercentage;
        this.secondMode = secondMode;
        this.secondModeFrequency = secondModeFrequency;
        this.secondModePercentage = secondModePercentage;
        this.categoryCounts = categoryCounts;
    }
}
