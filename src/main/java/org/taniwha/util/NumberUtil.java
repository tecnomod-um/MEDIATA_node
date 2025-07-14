package org.taniwha.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Locale;

public class NumberUtil {

    private static final Logger logger = LoggerFactory.getLogger(NumberUtil.class);
    private static final Locale COMMA_LOCALE = Locale.GERMANY;
    private static final NumberFormat COMMA_FORMAT = NumberFormat.getInstance(COMMA_LOCALE);

    public static double parseDouble(String value) throws ParseException {
        try {
            return Double.parseDouble(value.replace(",", "."));
        } catch (NumberFormatException e) {
            logger.trace("Standard parse failed for '{}', trying German NumberFormat...", value);
        }
        synchronized (COMMA_FORMAT) {
            Number number = COMMA_FORMAT.parse(value);
            return number.doubleValue();
        }
    }
}
