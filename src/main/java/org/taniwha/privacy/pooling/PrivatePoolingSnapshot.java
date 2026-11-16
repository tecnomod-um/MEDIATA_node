package org.taniwha.privacy.pooling;

import java.util.Map;

record PrivatePoolingSnapshot(PrivatePoolingPolicy policy, Map<String, PrivatePoolingRelease> releases) {}
