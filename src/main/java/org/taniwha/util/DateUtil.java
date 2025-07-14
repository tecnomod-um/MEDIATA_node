package org.taniwha.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

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

    private DateUtil() {
    }

    public static Optional<LocalDateTime> parseDate(String value) {
        for (String pattern : DATE_TIME_FORMATS) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            try {
                LocalDateTime dateTime = LocalDateTime.parse(value, formatter);
                return Optional.of(dateTime);
            } catch (DateTimeParseException ignored) {
                try {
                    LocalDate date = LocalDate.parse(value, formatter);
                    return Optional.of(date.atStartOfDay());
                } catch (DateTimeParseException ignored2) {
                    // Parsing failed for LocalDate, move to the next pattern.
                }
            }
        }
        return Optional.empty();
    }
}
