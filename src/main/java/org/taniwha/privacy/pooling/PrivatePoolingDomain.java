package org.taniwha.privacy.pooling;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.text.ParseException;
import org.taniwha.util.NumberUtil;
import org.taniwha.util.DateUtil;

public record PrivatePoolingDomain(List<Double> boundaries, List<String> categories,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) List<Double> values, String type,
                                   Boolean missingAllowed, Boolean unlistedCategoriesAllowed) {
    public PrivatePoolingDomain {
        if (type == null) type = boundaries == null ? "categorical" : "continuous";
        if (missingAllowed == null) missingAllowed = true;
        if (unlistedCategoriesAllowed == null) unlistedCategoriesAllowed = true;
    }
    public PrivatePoolingDomain(List<Double> boundaries, List<String> categories, List<Double> values, String type) {
        this(boundaries, categories, values, type, null, null);
    }
    public PrivatePoolingDomain(List<Double> boundaries, List<String> categories) {
        this(boundaries, categories, null, null);
    }
    public PrivatePoolingDomain(List<Double> boundaries, List<String> categories, List<Double> values) {
        this(boundaries, categories, values, null);
    }

    void validate() {
        if (!(type.equals("continuous") || type.equals("categorical") || type.equals("date"))
                || (type.equals("categorical") != (categories != null))) {
            throw new IllegalArgumentException("Invalid feature domain type");
        }
        if ((boundaries == null) == (categories == null)) {
            throw new IllegalArgumentException("Choose numerical boundaries or categorical labels");
        }
        if (values != null && (boundaries == null || values.size() != boundaries.size() - 1)) {
            throw new IllegalArgumentException("Each numerical bin must have one representative value");
        }
        if (boundaries != null) {
            if (boundaries.size() < 2 || boundaries.size() > 129) throw new IllegalArgumentException("Invalid boundaries");
            double previous = Double.NEGATIVE_INFINITY;
            for (Double limit : boundaries) {
                if (limit == null || !Double.isFinite(limit) || Math.abs(limit) > 1e9 || limit <= previous) {
                    throw new IllegalArgumentException("Boundaries must be finite and strictly increasing");
                }
                previous = limit;
            }
            for (int index = 0; values != null && index < values.size(); index++) {
                Double value = values.get(index);
                if (value == null || !Double.isFinite(value) || value < boundaries.get(index)
                        || value > boundaries.get(index + 1)) {
                    throw new IllegalArgumentException("Representative values must lie inside their bins");
                }
            }
        } else if (categories.isEmpty() || categories.size() > 128
                || !categories.stream().allMatch(PrivatePoolingPolicy::validName)
                || new HashSet<>(categories).size() != categories.size()) {
            throw new IllegalArgumentException("Invalid categories");
        }
    }

    int size() {
        return boundaries != null ? boundaries.size() : categories.size() + 2;
    }

    boolean structuralZero(int cell) {
        return (cell == size() - 1 && !missingAllowed)
                || (categories != null && cell == size() - 2 && !unlistedCategoriesAllowed);
    }

    int indexOf(String value) {
        int missing = size() - 1;
        if (value != null) value = value.trim();
        if (value == null || value.isBlank() || value.equalsIgnoreCase("NULL")) return missing;
        if (boundaries == null) {
            int index = categories.indexOf(value);
            return index < 0 ? missing - 1 : index;
        }
        try {
            double number = type.equals("date") ? DateUtil.parseDate(value).orElseThrow().toLocalDate().toEpochDay()
                    : NumberUtil.parseDouble(value);
            if (!Double.isFinite(number)) return missing;
            int position = Collections.binarySearch(boundaries, number);
            int bin = position < 0 ? -position - 2 : position;
            return Math.max(0, Math.min(missing - 1, bin));
        } catch (ParseException | IllegalArgumentException | java.util.NoSuchElementException invalid) {
            return missing;
        }
    }
}
