package org.taniwha.service;

import org.taniwha.util.DateUtil;
import org.taniwha.util.NumberUtil;
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

    void accept(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty() || "NULL".equalsIgnoreCase(value)) return;

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
        } catch (ParseException e) {
            allNumeric = false;
            allInteger = false;
        }

        categoricalCounts.merge(value, 1, Integer::sum);
        totalTextLength += value.length();
    }

    boolean canRelease() {
        return nonEmptyCount > MIN_DISCLOSURE_COUNT && permittedCategories();
    }

    private boolean permittedCategories() {
        return allNumeric || allDate || looksLikeNaturalLanguage()
                || categoricalCounts.values().stream().allMatch(count -> count > MIN_DISCLOSURE_COUNT);
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
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(MAX_CATEGORICAL_VALUES)
                .forEach(entry -> categories.add(entry.getKey()));

        return categories.isEmpty() ? List.of("Natural Language") : categories;
    }

    private boolean looksLikeNaturalLanguage() {
        if (categoricalCounts.isEmpty()) return true;

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
