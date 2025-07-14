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
}
