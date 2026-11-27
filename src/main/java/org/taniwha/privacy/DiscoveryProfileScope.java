package org.taniwha.privacy;

import java.util.Map;

// Whole files retain missingness; explicit row selections also use the shared query history.
public enum DiscoveryProfileScope {
    WHOLE_DATASET, FILTERED;

    public static DiscoveryProfileScope forFilters(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return WHOLE_DATASET;
        if (filters.get("conditions") instanceof Map<?, ?> conditions && conditions.isEmpty()) return WHOLE_DATASET;
        return FILTERED;
    }
}
