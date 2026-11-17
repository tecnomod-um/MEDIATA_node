package org.taniwha.privacy.pooling;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record PrivatePoolingHistogram(PrivatePoolingDomain domain, List<Long> counts,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) Double noiseVariance) {}
