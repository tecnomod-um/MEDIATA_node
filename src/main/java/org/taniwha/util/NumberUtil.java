package org.taniwha.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;

public class NumberUtil {

    private NumberUtil() {
    }

    private static final Logger logger = LoggerFactory.getLogger(NumberUtil.class);
    public static double parseDouble(String value) throws ParseException {
        if (value == null || value.isBlank()) {
            throw new ParseException("Numeric value is empty", 0);
        }

        String normalized = value.trim()
                .replace(" ", "")
                .replace("_", "")
                .replace("'", "")
                .replace("’", "");

        int lastDot = normalized.lastIndexOf('.');
        int lastComma = normalized.lastIndexOf(',');
        if (lastDot >= 0 && lastComma >= 0) {
            if (lastDot > lastComma) {
                requireValidGroupedNumber(normalized, ',', '.');
                normalized = normalized.replace(",", "");
            } else {
                requireValidGroupedNumber(normalized, '.', ',');
                normalized = normalized.replace(".", "").replace(',', '.');
            }
        } else if (lastComma >= 0) {
            normalized = normalizeSingleSeparator(normalized, ',');
        } else if (lastDot >= 0) {
            normalized = normalizeSingleSeparator(normalized, '.');
        }

        try {
            double parsed = Double.parseDouble(normalized);
            if (!Double.isFinite(parsed)) {
                throw new NumberFormatException("Non-finite value");
            }
            return parsed;
        } catch (NumberFormatException e) {
            logger.trace("Strict numeric parse failed for '{}'", value);
            ParseException parseException = new ParseException("Invalid numeric value: " + value, 0);
            parseException.initCause(e);
            throw parseException;
        }
    }

    private static String normalizeSingleSeparator(String value, char separator) throws ParseException {
        long occurrences = value.chars().filter(character -> character == separator).count();
        if (occurrences == 1) {
            return separator == ',' ? value.replace(',', '.') : value;
        }

        String groupingPattern = "[+-]?\\d{1,3}(\\" + separator + "\\d{3})+";
        if (!value.matches(groupingPattern)) {
            throw new ParseException("Invalid numeric value: " + value, 0);
        }
        return value.replace(String.valueOf(separator), "");
    }

    private static void requireValidGroupedNumber(String value, char grouping, char decimal)
            throws ParseException {
        String pattern = "[+-]?\\d{1,3}(\\" + grouping + "\\d{3})+\\" + decimal + "\\d+";
        if (!value.matches(pattern)) {
            throw new ParseException("Invalid numeric value: " + value, 0);
        }
    }
}
