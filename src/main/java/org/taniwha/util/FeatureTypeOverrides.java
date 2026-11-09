package org.taniwha.util;

import java.util.*;

/** Request-scoped interpretations; never mutate a dataset or another user's schema. */
public final class FeatureTypeOverrides {
    private FeatureTypeOverrides() {}

    public static Map<String, String> validate(Map<String, String> requested) {
        if (requested == null || requested.isEmpty()) return Map.of();
        Map<String, String> result = new HashMap<>();
        requested.forEach((column, type) -> {
            if (column == null || column.isBlank() || type == null
                    || !(type.equals("continuous") || type.equals("categorical")))
                throw new IllegalArgumentException("Invalid feature type override");
            result.put(column, type);
        });
        return Map.copyOf(result);
    }
}
