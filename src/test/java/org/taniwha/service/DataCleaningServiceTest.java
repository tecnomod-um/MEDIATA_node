package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class DataCleaningServiceTest {

    private DataCleaningService svc;

    @BeforeEach
    void setUp() {
        svc = new DataCleaningService();
    }

    @Test
    void removeDuplicates_shouldDropExactDuplicates() {
        Map<String, String> row1 = Map.of("a", "1", "b", "x");
        Map<String, String> row3 = Map.of("a", "2", "b", "y");

        var input = new ArrayList<>(List.of(row1, row1, row3, row1));
        var out = svc.removeDuplicates(input);

        assertThat(out)
                .hasSize(2)
                .containsExactlyInAnyOrder(row1, row3);
    }

    @Test
    void removeEmptyRows_shouldFilterOutRowsAllEmptyOrNull() {
        Map<String, String> empty1 = new HashMap<>();
        empty1.put("a", "");
        empty1.put("b", "  ");
        Map<String, String> empty2 = new HashMap<>();
        empty2.put("a", null);
        empty2.put("b", "");
        Map<String, String> nonEmpty = new HashMap<>();
        nonEmpty.put("a", "foo");
        nonEmpty.put("b", "");
        var input = List.of(empty1, empty2, nonEmpty);
        var out = svc.removeEmptyRows(input);

        assertThat(out).containsExactly(nonEmpty);
    }

    @Test
    void isEmptyRow_shouldDetectEmptyAndNullValues() {
        Map<String, String> empty = new HashMap<>();
        empty.put("x", "");
        empty.put("y", "   ");
        empty.put("z", null);

        Map<String, String> notEmpty = new HashMap<>();
        notEmpty.put("x", "");
        notEmpty.put("y", "val");

        assertThat(svc.isEmptyRow(empty)).isTrue();
        assertThat(svc.isEmptyRow(notEmpty)).isFalse();
    }

    @Test
    void dedupeKey_shouldProduceDeterministicString() {
        Map<String, String> row = new HashMap<>();
        row.put("b", "B");
        row.put("a", "A");
        row.put("c", "");

        String key = svc.dedupeKey(row);
        assertThat(key).isEqualTo("a=A|b=B|c=");
    }

    @Test
    void standardizeDates_defaultFormat_appliesIso() {
        Map<String, String> r1 = new HashMap<>();
        r1.put("d1", "2020-01-02");
        r1.put("other", "x");
        Map<String, String> r2 = new HashMap<>();
        r2.put("d1", "2019-12-31");
        r2.put("foo", null);

        var list = new ArrayList<>(List.of(r1, r2));
        var out = svc.standardizeDates(list, null);

        assertThat(out.get(0).get("d1")).isEqualTo("2020-01-02");
        assertThat(out.get(1).get("d1")).isEqualTo("2019-12-31");
        assertThat(out.get(0).get("other")).isEqualTo("x");
        assertThat(out.get(1).get("foo")).isNull();
    }

    @Test
    void standardizeDates_customPattern_respectsPattern() {
        Map<String, String> row = new HashMap<>();
        row.put("date", "2020-01-02");

        var out = svc.standardizeDates(
                new ArrayList<>(List.of(row)),
                "DD/MM/YYYY"
        );

        assertThat(out).hasSize(1);
        assertThat(out.get(0).get("date")).isEqualTo("02/01/2020");
    }

    @Test
    void standardizeDatesInPlace_changesRowInline() {
        Map<String, String> r = new HashMap<>();
        r.put("a", "02-01-2020");
        r.put("b", "notadate");
        svc.standardizeDatesInPlace(r, "yyyy/MM/dd");

        String expected = LocalDate.parse("2020-01-02", DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                .format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertThat(r.get("a")).isEqualTo(expected);
        assertThat(r.get("b")).isEqualTo("notadate");
    }

    @Test
    void removeDuplicates_emptyList_returnsEmpty() {
        List<Map<String, String>> empty = Collections.emptyList();
        var out = svc.removeDuplicates(empty);
        assertThat(out).isEmpty();
    }

    @Test
    void removeEmptyRows_mixture_keepsOnlyNonEmpty() {
        Map<String, String> row1 = new HashMap<>();
        row1.put("a", " ");
        row1.put("b", null);

        Map<String, String> row2 = new HashMap<>();
        row2.put("a", "val");
        row2.put("b", "");
        var input = List.of(row1, row2);
        var out = svc.removeEmptyRows(input);

        assertThat(out).containsExactly(row2);
    }

    @Test
    void isEmptyRow_singleNullKey_returnsTrue() {
        Map<String, String> onlyNull = new HashMap<>();
        onlyNull.put("key", null);
        assertThat(svc.isEmptyRow(onlyNull)).isTrue();
    }

    @Test
    void dedupeKey_emptyMap_returnsEmptyString() {
        assertThat(svc.dedupeKey(Collections.emptyMap())).isEmpty();
    }

    @Test
    void standardizeDates_blankFormat_usesIsoDefault() {
        Map<String, String> row = new HashMap<>();
        row.put("d", "2021/04/05 12:30:00");
        var out = svc.standardizeDates(new ArrayList<>(List.of(row)), "");
        assertThat(out.get(0).get("d")).isEqualTo("2021-04-05");
    }

    @Test
    void standardizeDates_nonDateField_unmodified() {
        Map<String, String> row = new HashMap<>();
        row.put("x", "notadate");
        var out = svc.standardizeDates(new ArrayList<>(List.of(row)), null);
        assertThat(out.get(0).get("x")).isEqualTo("notadate");
    }

    @Test
    void standardizeDatesInPlace_blankPattern_defaultsAndLeavesNonDate() {
        Map<String, String> row = new HashMap<>();
        row.put("d", "15.08.2020");
        row.put("n", "nope");
        svc.standardizeDatesInPlace(row, "   ");
        assertThat(row.get("d")).isEqualTo("2020-08-15");
        assertThat(row.get("n")).isEqualTo("nope");
    }

    @Test
    @DisplayName("standardizeDates recognizes multiple input formats")
    void standardizeDates_variousInputFormats() {
        Map<String, String> r1 = new HashMap<>(), r2 = new HashMap<>(), r3 = new HashMap<>();
        r1.put("d", "02/01/20");
        r2.put("d", "03.02.21 15:30:00");
        r3.put("d", "2022/03/04 08:05:06");

        List<Map<String, String>> list = new ArrayList<>(List.of(r1, r2, r3));
        var out = svc.standardizeDates(list, null);
        assertThat(out).extracting(m -> m.get("d"))
                .containsExactly("2020-01-02", "2021-02-03", "2022-03-04");
    }

    @Test
    @DisplayName("standardizeDatesInPlace leaves null or blank values untouched")
    void standardizeDatesInPlace_skipsNullOrBlank() {
        Map<String, String> row = new HashMap<>();
        row.put("a", null);
        row.put("b", "");
        row.put("c", "  ");
        row.put("d", "05-06-2023");

        svc.standardizeDatesInPlace(row, null);
        assertThat(row.get("a")).isNull();
        assertThat(row.get("b")).isEmpty();
        assertThat(row.get("c")).isEqualTo("  ");
        assertThat(row.get("d")).isEqualTo("2023-06-05");
    }
}
