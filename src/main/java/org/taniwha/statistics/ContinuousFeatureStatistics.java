package org.taniwha.statistics;

import lombok.Getter;

import java.util.List;

@Getter
public class ContinuousFeatureStatistics extends FeatureStatistics {
    private final int cardinality;
    private final double min;
    private final double max;
    private final double mean;
    private final double stdDev;
    private final double qrt1;
    private final double median;
    private final double qrt3;
    private List<Double> histogram;
    private List<String> binRanges;
    private String histogramStatus = "AVAILABLE";
    @com.fasterxml.jackson.annotation.JsonIgnore
    private double histogramBinWidth;
    public void setHistogramBinWidth(double width) { this.histogramBinWidth = width; }
    public void suppressHistogram() {
        histogram = List.of();
        binRanges = List.of();
        histogramStatus = "WITHHELD";
    }
    public void coarsenHistogram(List<Double> counts, List<String> ranges) {
        histogram = List.copyOf(counts);
        binRanges = List.copyOf(ranges);
        histogramStatus = "COARSENED";
    }
    private final List<Double> outliers;
    private List<NumericValueCount> valueCounts = List.of();
    private Double sumCubedDeviations;
    private Double sumFourthDeviations;
    private Double sampleStdDev;
    private Double skewness;
    private Double kurtosis;
    private Double meanCiLower;
    private Double meanCiUpper;
    private List<Double> modes = List.of();
    private Long modeFrequency;
    private String modeStatus = "Unavailable";

    public void setSummary(ContinuousSummary summary) {
        sumCubedDeviations = summary.sumCubedDeviations();
        sumFourthDeviations = summary.sumFourthDeviations();
        sampleStdDev = summary.sampleStdDev();
        skewness = summary.skewness();
        kurtosis = summary.kurtosis();
        meanCiLower = summary.meanCiLower();
        meanCiUpper = summary.meanCiUpper();
        modes = summary.modes();
        modeFrequency = summary.modeFrequency();
        modeStatus = summary.modeStatus();
    }

    public void setValueCounts(List<NumericValueCount> valueCounts) {
        this.valueCounts = List.copyOf(valueCounts);
    }

    public ContinuousFeatureStatistics(String featureName, long count, double percentMissing, long missingValuesCount, int cardinality, double min, double max, double mean, double stdDev, double qrt1, double median, double qrt3, List<Double> histogramBins, List<String> binRanges, List<Double> outliers) {
        super(featureName, count, percentMissing, missingValuesCount);
        this.cardinality = cardinality;
        this.min = min;
        this.max = max;
        this.mean = mean;
        this.stdDev = stdDev;
        this.qrt1 = qrt1;
        this.median = median;
        this.qrt3 = qrt3;
        this.histogram = histogramBins;
        this.binRanges = binRanges;
        this.outliers = outliers;
    }
}
