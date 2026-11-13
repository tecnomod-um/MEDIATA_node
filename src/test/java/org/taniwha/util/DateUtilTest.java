package org.taniwha.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class DateUtilTest {

    @Test
    void parseDate_validFormats() {
        Optional<LocalDateTime> dt1 = DateUtil.parseDate("2020-02-29");
        assertThat(dt1).hasValue(LocalDate.of(2020, 2, 29).atStartOfDay());

        Optional<LocalDateTime> dt2 = DateUtil.parseDate("29/02/20");
        assertThat(dt2).hasValue(LocalDate.of(2020, 2, 29).atStartOfDay());

        Optional<LocalDateTime> dt3 = DateUtil.parseDate("2020-02-29T12:34:56.000Z");
        assertThat(dt3).isPresent();
    }

    @Test
    void parseDate_smartResolvesEndOfMonth() {
        Optional<LocalDateTime> result = DateUtil.parseDate("31/02/2020");
        assertThat(result)
                .hasValue(LocalDate.of(2020, 2, 29).atStartOfDay());
    }

    @Test
    void parseDate_unparsable_returnsEmpty() {
        assertThat(DateUtil.parseDate("not a date")).isEmpty();
    }

    @Test
    void optimizedParserPreservesLegacyFormatsPrecedenceAndInvalidInputs() {
        String[] patterns = {"dd/MM/yy HH:mm:ss", "dd/MM/yy", "dd.MM.yy HH:mm:ss", "dd.MM.yy",
                "d/MM/yyyy HH:mm:ss", "d/MM/yyyy", "dd/M/yyyy HH:mm:ss", "dd/M/yyyy",
                "d.MM.yyyy HH:mm:ss", "d.MM.yyyy", "dd.M.yyyy HH:mm:ss", "dd.M.yyyy",
                "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy", "MM/dd/yyyy HH:mm:ss", "MM/dd/yyyy",
                "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd", "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy",
                "MM-dd-yyyy HH:mm:ss", "MM-dd-yyyy", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd",
                "dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy", "MM.dd.yyyy HH:mm:ss", "MM.dd.yyyy",
                "yyyy.MM.dd HH:mm:ss", "yyyy.MM.dd", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"};
        java.util.List<String> inputs = new java.util.ArrayList<>(java.util.List.of(
                "31/02/2020", "2020-01-01 24:00:00", "2020-01-01 25:00:00", "2020-01-01suffix",
                "1", "12.50", "Female", "", "29/02/19", "2020-13-01", "02/03/2020"));
        LocalDateTime example = LocalDateTime.of(2020, 2, 29, 12, 34, 56);
        for (String pattern : patterns) inputs.add(example.format(java.time.format.DateTimeFormatter.ofPattern(pattern)));
        for (String input : inputs) {
            Optional<LocalDateTime> legacy = Optional.empty();
            for (String pattern : patterns) {
                var formatter = java.time.format.DateTimeFormatter.ofPattern(pattern);
                try { legacy = Optional.of(LocalDateTime.parse(input, formatter)); break; }
                catch (java.time.format.DateTimeParseException ignored) {
                    try { legacy = Optional.of(LocalDate.parse(input, formatter).atStartOfDay()); break; }
                    catch (java.time.format.DateTimeParseException ignoredAgain) { }
                }
            }
            assertThat(DateUtil.parseDate(input)).as(input).isEqualTo(legacy);
        }
    }
}
