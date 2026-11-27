package org.taniwha.statistics;

import lombok.Getter;

@Getter
public class OmittedFeatureStatistics extends FeatureStatistics {
    private final String reason;
    private final String type;
    private final boolean countsReleased;

    public OmittedFeatureStatistics(String featureName, long count, double percentMissing, long missingValuesCount, String reason) {
        this(featureName, count, percentMissing, missingValuesCount, reason, null, false);
    }

    public OmittedFeatureStatistics(String featureName, long count, double percentMissing, long missingValuesCount,
                                    String reason, String type, boolean countsReleased) {
        super(featureName, count, percentMissing, missingValuesCount);
        this.reason = reason;
        this.type = type;
        this.countsReleased = countsReleased;
    }
}
