package org.taniwha.privacy.pooling;

import java.util.Map;

public record PrivatePoolingRelease(int version, String group, double rowEpsilon, double noiseVariance,
                                    Map<String, PrivatePoolingHistogram> features) {}
