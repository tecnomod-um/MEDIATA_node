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
}
