package org.taniwha.privacy.pooling;

import java.util.Map;

record PrivatePoolingSnapshot(PrivatePoolingPolicy policy, Map<String, PrivatePoolingRelease> releases, int metadataVersion,
                              Double allocatedAllowance, Boolean coversDataset) {
    PrivatePoolingSnapshot(PrivatePoolingPolicy policy, Map<String, PrivatePoolingRelease> releases) {
        this(policy, releases, 0, null, null);
    }
}
