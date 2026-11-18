package org.taniwha.service;

import org.taniwha.util.DateUtil;
import org.taniwha.util.NumberUtil;
import org.taniwha.dto.DatasetElementDTO;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class ColumnAccumulator {
    private static final int MAX_CATEGORICAL_VALUES = 20;
    private static final int MIN_DISCLOSURE_COUNT = 5;
    private static final int MIN_RECORDS_FOR_UNIQUE_FILTER = 10;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private boolean allNumeric = true;
    private boolean allInteger = true;
    private boolean allDate = true;
    private long nonEmptyCount = 0;
    private long totalTextLength = 0;
    private Double min = null;
    private Double max = null;
    private LocalDateTime earliest = null;
    private LocalDateTime latest = null;
    private final LinkedHashMap<String, Integer> categoricalCounts = new LinkedHashMap<>();
    private boolean categoricalOverflow;
    private boolean missingObserved;
    private Long firstInteger;
    private long integerStep;
    private boolean integralValues = true;

    void accept(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty() || "NULL".equalsIgnoreCase(value)) {
            missingObserved = true;
            return;
        }

        nonEmptyCount++;

        Optional<LocalDateTime> parsedDate = DateUtil.parseDate(value);
        if (parsedDate.isPresent()) {
            LocalDateTime date = parsedDate.get();
            if (earliest == null || date.isBefore(earliest)) earliest = date;
            if (latest == null || date.isAfter(latest)) latest = date;
        } else {
            allDate = false;
        }

        try {
            double parsed = NumberUtil.parseDouble(value);
            if (min == null || parsed < min) min = parsed;
            if (max == null || parsed > max) max = parsed;
            if (!looksInteger(value, parsed)) allInteger = false;
            if (!Double.isFinite(parsed) || Math.abs(parsed) > 1e9 || parsed != Math.rint(parsed)) integralValues = false;
            if (integralValues) {
                long number = (long) parsed;
                if (firstInteger == null) firstInteger = number;
                integerStep = gcd(integerStep, Math.abs(number - firstInteger));
            }
        } catch (ParseException e) {
            allNumeric = false;
            allInteger = false;
            integralValues = false;
        }

        if (!categoricalOverflow) {
            categoricalCounts.merge(value, 1, Integer::sum);
            if (categoricalCounts.size() > 200) {
                categoricalOverflow = true;
                categoricalCounts.clear();
            }
        }
        totalTextLength += value.length();
    }

    boolean canRelease() {
        return nonEmptyCount > MIN_DISCLOSURE_COUNT && permittedCategories();
    }

    DatasetElementDTO toElement(String column) {
        var result = new DatasetElementDTO(column, toElementValues());
        result.setDataType(allDate ? "date" : allNumeric ? allInteger ? "integer" : "double"
                : looksLikeNaturalLanguage() ? "text" : "categorical");
        // These are unfiltered schema facts, published under the same metadata
        // disclosure policy as limits and labels. They are not DP statistics.
        result.setMissingAllowed(missingObserved);
        if (allNumeric && integralValues && firstInteger != null) result.setIntegerStep(Math.max(1, integerStep));
        if (!allNumeric && !allDate && !looksLikeNaturalLanguage())
            result.setUnlistedCategoriesAllowed(categoricalCounts.size() > MAX_CATEGORICAL_VALUES
                    || categoricalCounts.values().stream().anyMatch(count -> count <= MIN_DISCLOSURE_COUNT));
        return result;
    }

    private static long gcd(long left, long right) {
        while (right != 0) { long remainder = left % right; left = right; right = remainder; }
        return left;
    }

    private boolean permittedCategories() {
        return allNumeric || allDate || looksLikeNaturalLanguage()
                || categoricalCounts.values().stream().anyMatch(count -> count > MIN_DISCLOSURE_COUNT);
    }

    List<String> toElementValues() {
        if (nonEmptyCount == 0) return List.of("Natural Language");

        if (allDate && earliest != null && latest != null) {
            return List.of(
                    "date",
                    "earliest:" + DATE_FORMAT.format(earliest.toLocalDate()),
                    "latest:" + DATE_FORMAT.format(latest.toLocalDate())
            );
        }

        if (allNumeric && min != null && max != null) {
            return List.of(
                    allInteger ? "integer" : "double",
                    "min:" + trimNumber(min),
                    "max:" + trimNumber(max)
            );
        }

        if (looksLikeNaturalLanguage()) {
            return List.of("Natural Language");
        }

        List<String> categories = new ArrayList<>();
        categoricalCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > MIN_DISCLOSURE_COUNT)
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(MAX_CATEGORICAL_VALUES)
                .forEach(entry -> categories.add(entry.getKey()));

        return categories.isEmpty() ? List.of("Natural Language") : categories;
    }

    private boolean looksLikeNaturalLanguage() {
        if (categoricalOverflow || categoricalCounts.isEmpty()) return true;

        double uniquePercentage = (double) categoricalCounts.size() / (double) nonEmptyCount * 100.0;
        double averageLength = (double) totalTextLength / (double) nonEmptyCount;
        boolean manyUniqueValues = nonEmptyCount > MIN_RECORDS_FOR_UNIQUE_FILTER && uniquePercentage >= 50.0;
        boolean tooManyCategories = categoricalCounts.size() > 200;
        boolean longText = averageLength >= 30.0;
        boolean uuidLike = categoricalCounts.keySet().stream().anyMatch(value -> value.matches("[0-9a-fA-F-]{36}"));

        return tooManyCategories || uuidLike || (manyUniqueValues && longText);
    }

    private static boolean looksInteger(String rawValue, double parsed) {
        if (Math.abs(parsed - Math.rint(parsed)) > 1e-9) return false;
        return !String.valueOf(rawValue).contains(".");
    }

    private static String trimNumber(Double value) {
        if (value == null) return "";
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.valueOf((long) Math.rint(value));
        }
        String text = String.format(Locale.US, "%.12f", value);
        text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        return text;
    }
}
