package org.taniwha.privacy.pooling;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record PrivatePoolingRelease(int version, String group, double rowEpsilon,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) Double noiseVariance,
                                    Map<String, PrivatePoolingHistogram> features) {
    @JsonProperty(value = "privacyUnit", access = JsonProperty.Access.READ_ONLY)
    public String privacyUnit() {
        return "one-row-add-remove";
    }

    @JsonProperty(value = "privacyScope", access = JsonProperty.Access.READ_ONLY)
    public String privacyScope() {
        return "protected-histograms-only";
    }

    // Overlapping datasets and changed versions are not composed by this allowance.
    @JsonProperty(value = "budgetScope", access = JsonProperty.Access.READ_ONLY)
    public String budgetScope() {
        return "dataset-version";
    }
}
