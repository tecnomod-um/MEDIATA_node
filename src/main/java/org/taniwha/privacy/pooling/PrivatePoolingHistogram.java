package org.taniwha.privacy.pooling;

import java.util.List;

public record PrivatePoolingHistogram(PrivatePoolingDomain domain, List<Long> counts) {}
