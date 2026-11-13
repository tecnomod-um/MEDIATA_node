package org.taniwha.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.text.ParsePosition;
import java.util.Arrays;

public class DateUtil {

    private static final String[] DATE_TIME_FORMATS = {
            "dd/MM/yy HH:mm:ss", "dd/MM/yy",
            "dd.MM.yy HH:mm:ss", "dd.MM.yy",
            "d/MM/yyyy HH:mm:ss", "d/MM/yyyy",
            "dd/M/yyyy HH:mm:ss", "dd/M/yyyy",
            "d.MM.yyyy HH:mm:ss", "d.MM.yyyy",
            "dd.M.yyyy HH:mm:ss", "dd.M.yyyy",
            "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy",
            "MM/dd/yyyy HH:mm:ss", "MM/dd/yyyy",
            "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd",
            "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy",
            "MM-dd-yyyy HH:mm:ss", "MM-dd-yyyy",
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd",
            "dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy",
            "MM.dd.yyyy HH:mm:ss", "MM.dd.yyyy",
            "yyyy.MM.dd HH:mm:ss", "yyyy.MM.dd",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    };

    private static final DateTimeFormatter[] FORMATTERS = Arrays.stream(DATE_TIME_FORMATS)
            .map(DateTimeFormatter::ofPattern).toArray(DateTimeFormatter[]::new);

    private DateUtil() {}

    public static Optional<LocalDateTime> parseDate(String value) {
        if (value == null || value.isEmpty()) return Optional.empty();
        for (int i = 0; i < FORMATTERS.length; i++) {
            DateTimeFormatter formatter = FORMATTERS[i];
            // Most discovery values are numbers/categories. Reject syntax mismatches
            // without constructing exceptions or rebuilding formatters per cell.
            ParsePosition position = new ParsePosition(0);
            if (formatter.parseUnresolved(value, position) == null || position.getIndex() != value.length()) continue;
            try {
                return Optional.of(DATE_TIME_FORMATS[i].contains("HH")
                        ? LocalDateTime.parse(value, formatter)
                        : LocalDate.parse(value, formatter).atStartOfDay());
            } catch (DateTimeParseException ignored) {
                // Syntactically valid fields may still contain an invalid date/time.
            }
        }
        return Optional.empty();
    }
}
