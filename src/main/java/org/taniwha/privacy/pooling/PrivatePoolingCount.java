package org.taniwha.privacy.pooling;

import java.util.List;

// A separately protected group size, never an exact row count.
public record PrivatePoolingCount(long count, double noiseVariance, List<Double> noiseComponents) {}
