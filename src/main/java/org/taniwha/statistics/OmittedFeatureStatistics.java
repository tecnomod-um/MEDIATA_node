package org.taniwha.statistics;

import lombok.Getter;

@Getter
public class OmittedFeatureStatistics extends FeatureStatistics {
    private final String reason;

    public OmittedFeatureStatistics(String featureName, long count, double percentMissing, long missingValuesCount, String reason) {
        super(featureName, count, percentMissing, missingValuesCount);
        this.reason = reason;
    }
}
