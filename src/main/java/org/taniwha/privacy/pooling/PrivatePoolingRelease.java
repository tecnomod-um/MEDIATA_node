package org.taniwha.privacy.pooling;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

public record PrivatePoolingRelease(int version, String group, double rowEpsilon,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) Double noiseVariance,
                                    Map<String, PrivatePoolingHistogram> features) {}
