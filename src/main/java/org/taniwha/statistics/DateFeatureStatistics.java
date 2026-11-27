package org.taniwha.statistics;

import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
public class DateFeatureStatistics extends FeatureStatistics {
    private final String earliestDate;
    private final String latestDate;
    private Map<String, Long> dateHistogram;
    private final int cardinality;
    private List<DateHistogramBin> dateBins = List.of();
    private String histogramStatus = "AVAILABLE";
    private Long sumEpochDays;
    private Double sumSquaredDeviations;
    private final List<String> outliers;
    private final String mean;
    private final double stdDev;
    private final String median;
    private final String q1;
    private final String q3;

    public void setMoments(long sumEpochDays, double sumSquaredDeviations) {
        this.sumEpochDays = sumEpochDays;
        this.sumSquaredDeviations = sumSquaredDeviations;
    }

    public void coarsenHistogram(List<DateHistogramBin> bins) {
        dateHistogram = Map.of();
        dateBins = List.copyOf(bins);
        histogramStatus = "COARSENED";
    }

    public DateFeatureStatistics(String featureName, long count, double percentMissing, long missingValuesCount, String earliestDate, String latestDate, Map<String, Long> dateHistogram, List<String> outliers, String mean, double stdDev, String median, String q1, String q3) {
        super(featureName, count, percentMissing, missingValuesCount);
        this.earliestDate = earliestDate;
        this.latestDate = latestDate;
        this.dateHistogram = dateHistogram;
        this.cardinality = dateHistogram.size();
        this.outliers = outliers;
        this.mean = mean;
        this.stdDev = stdDev;
        this.median = median;
        this.q1 = q1;
        this.q3 = q3;
    }
}
