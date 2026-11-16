package org.taniwha.privacy.pooling;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.text.ParseException;
import org.taniwha.util.NumberUtil;

public record PrivatePoolingDomain(List<Double> boundaries, List<String> categories) {
    void validate() {
        if ((boundaries == null) == (categories == null)) {
            throw new IllegalArgumentException("Choose numerical boundaries or categorical labels");
        }
        if (boundaries != null) {
            if (boundaries.size() < 2 || boundaries.size() > 65) throw new IllegalArgumentException("Invalid boundaries");
            double previous = Double.NEGATIVE_INFINITY;
            for (Double limit : boundaries) {
                if (limit == null || !Double.isFinite(limit) || Math.abs(limit) > 1e9 || limit <= previous) {
                    throw new IllegalArgumentException("Boundaries must be finite and strictly increasing");
                }
                previous = limit;
            }
        } else if (categories.isEmpty() || categories.size() > 64
                || !categories.stream().allMatch(PrivatePoolingPolicy::validName)
                || categories.contains("Other") || new HashSet<>(categories).size() != categories.size()) {
            throw new IllegalArgumentException("Invalid categories");
        }
    }

    int size() {
        return boundaries != null ? boundaries.size() : categories.size() + 2;
    }

    int indexOf(String value) {
        int missing = size() - 1;
        if (value == null || value.isBlank() || value.equalsIgnoreCase("NULL")) return missing;
        if (boundaries == null) {
            int index = categories.indexOf(value);
            return index < 0 ? missing - 1 : index;
        }
        try {
            double number = NumberUtil.parseDouble(value);
            if (!Double.isFinite(number)) return missing;
            int position = Collections.binarySearch(boundaries, number);
            int bin = position < 0 ? -position - 2 : position;
            return Math.max(0, Math.min(missing - 1, bin));
        } catch (ParseException | NumberFormatException invalid) {
            return missing;
        }
    }
}
