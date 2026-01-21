package org.taniwha.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class DateUtilExtendedTest {

    @Test
    void parseDate_withCommonISOFormat_shouldParse() {
        Optional<LocalDateTime> result = DateUtil.parseDate("2023-12-25");
        assertThat(result).isPresent();
        assertThat(result.get().getYear()).isEqualTo(2023);
        assertThat(result.get().getMonthValue()).isEqualTo(12);
        assertThat(result.get().getDayOfMonth()).isEqualTo(25);
    }

    @Test
    void parseDate_withSlashFormats_shouldParse() {
        assertThat(DateUtil.parseDate("25/12/2023")).isPresent();
        assertThat(DateUtil.parseDate("12/25/2023")).isPresent();
        assertThat(DateUtil.parseDate("2023/12/25")).isPresent();
    }

    @Test
    void parseDate_withDotFormats_shouldParse() {
        assertThat(DateUtil.parseDate("25.12.2023")).isPresent();
        assertThat(DateUtil.parseDate("12.25.2023")).isPresent();
    }

    @Test
    void parseDate_withDashFormats_shouldParse() {
        assertThat(DateUtil.parseDate("25-12-2023")).isPresent();
        assertThat(DateUtil.parseDate("2023-12-25")).isPresent();
    }

    @Test
    void parseDate_withTimeComponent_shouldParse() {
        Optional<LocalDateTime> result = DateUtil.parseDate("25/12/2023 14:30:45");
        assertThat(result).isPresent();
        assertThat(result.get().getHour()).isEqualTo(14);
        assertThat(result.get().getMinute()).isEqualTo(30);
    }

    @Test
    void parseDate_withInvalidFormats_shouldReturnEmpty() {
        assertThat(DateUtil.parseDate("not a date")).isEmpty();
        assertThat(DateUtil.parseDate("")).isEmpty();
        assertThat(DateUtil.parseDate("32/13/2023")).isEmpty();
    }

    @Test
    void parseDate_withNullInput_shouldReturnEmpty() {
        assertThat(DateUtil.parseDate(null)).isEmpty();
    }

    @Test
    void parseDate_withLeapYear_shouldParse() {
        Optional<LocalDateTime> result = DateUtil.parseDate("2024-02-29");
        assertThat(result).isPresent();
        assertThat(result.get().getDayOfMonth()).isEqualTo(29);
    }

    @Test
    void parseDate_withEdgeDates_shouldParse() {
        assertThat(DateUtil.parseDate("2023-01-01")).isPresent();
        assertThat(DateUtil.parseDate("2023-12-31")).isPresent();
    }

    @Test
    void parseDate_withISOFormat_shouldParse() {
        Optional<LocalDateTime> result = DateUtil.parseDate("2023-12-25T14:30:45.123Z");
        assertThat(result).isPresent();
        assertThat(result.get().getYear()).isEqualTo(2023);
    }
}
