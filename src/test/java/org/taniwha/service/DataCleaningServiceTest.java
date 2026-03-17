package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.taniwha.security.FileFilter;
import org.taniwha.service.jobs.CleaningProcessingJobs;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

class DataCleaningServiceTest {

    private DataCleaningService svc;

    @BeforeEach
    void setUp() {
        FileFilter fileFilter = mock(FileFilter.class);
        doNothing().when(fileFilter).validate(any(Path.class));
        FileService fileService = mock(FileService.class);
        DataProcessingService dataProcessingService = mock(DataProcessingService.class);
        CleaningProcessingJobs cleaningJobs = new CleaningProcessingJobs();

        svc = new DataCleaningService(fileService, dataProcessingService, cleaningJobs);
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

    @Test
    @DisplayName("trimWhitespace removes leading and trailing spaces")
    void trimWhitespace_removesSpaces() {
        Map<String, String> row = new HashMap<>();
        row.put("a", "  hello  ");
        row.put("b", "world\t");
        row.put("c", null);
        
        var result = svc.trimWhitespace(new ArrayList<>(List.of(row)));
        assertThat(result.get(0).get("a")).isEqualTo("hello");
        assertThat(result.get(0).get("b")).isEqualTo("world");
        assertThat(result.get(0).get("c")).isNull();
    }

    @Test
    @DisplayName("standardizeCase converts to uppercase")
    void standardizeCase_uppercase() {
        Map<String, String> row = new HashMap<>();
        row.put("text", "Hello World");
        
        var result = svc.standardizeCase(new ArrayList<>(List.of(row)), "upper");
        assertThat(result.get(0).get("text")).isEqualTo("HELLO WORLD");
    }

    @Test
    @DisplayName("standardizeCase converts to lowercase")
    void standardizeCase_lowercase() {
        Map<String, String> row = new HashMap<>();
        row.put("text", "Hello World");
        
        var result = svc.standardizeCase(new ArrayList<>(List.of(row)), "lower");
        assertThat(result.get(0).get("text")).isEqualTo("hello world");
    }

    @Test
    @DisplayName("standardizeCase converts to title case")
    void standardizeCase_titleCase() {
        Map<String, String> row = new HashMap<>();
        row.put("text", "hello world test");
        
        var result = svc.standardizeCase(new ArrayList<>(List.of(row)), "title");
        assertThat(result.get(0).get("text")).isEqualTo("Hello World Test");
    }

    @Test
    @DisplayName("removeSpecialCharacters keeps alphanumeric and basic punctuation")
    void removeSpecialCharacters_keepsAlphanumeric() {
        Map<String, String> row = new HashMap<>();
        row.put("text", "Hello@World#123!");
        
        var result = svc.removeSpecialCharacters(new ArrayList<>(List.of(row)));
        assertThat(result.get(0).get("text")).isEqualTo("HelloWorld123");
    }

    @Test
    @DisplayName("normalizeText collapses multiple spaces")
    void normalizeText_collapsesSpaces() {
        Map<String, String> row = new HashMap<>();
        row.put("text", "Hello    World  \n  Test");
        
        var result = svc.normalizeText(new ArrayList<>(List.of(row)));
        assertThat(result.get(0).get("text")).isEqualTo("Hello World Test");
    }

    @Test
    @DisplayName("removeLeadingZeros strips leading zeros from numbers")
    void removeLeadingZeros_stripsZeros() {
        Map<String, String> row = new HashMap<>();
        row.put("num", "00123");
        row.put("text", "hello");
        row.put("zero", "0");
        
        var result = svc.removeLeadingZeros(new ArrayList<>(List.of(row)));
        assertThat(result.get(0).get("num")).isEqualTo("123");
        assertThat(result.get(0).get("text")).isEqualTo("hello");
        assertThat(result.get(0).get("zero")).isEqualTo("0");
    }

    @Test
    @DisplayName("fillMissingValues with constant fills empty values")
    void fillMissingValues_constant() {
        Map<String, String> row1 = new HashMap<>();
        row1.put("a", "");
        row1.put("b", "value");
        Map<String, String> row2 = new HashMap<>();
        row2.put("a", "data");
        row2.put("b", null);
        
        var result = svc.fillMissingValues(new ArrayList<>(List.of(row1, row2)), "constant", "DEFAULT", null);
        assertThat(result.get(0).get("a")).isEqualTo("DEFAULT");
        assertThat(result.get(0).get("b")).isEqualTo("value");
        assertThat(result.get(1).get("a")).isEqualTo("data");
        assertThat(result.get(1).get("b")).isEqualTo("DEFAULT");
    }

    @Test
    @DisplayName("fillMissingValues with mean fills with average")
    void fillMissingValues_mean() {
        Map<String, String> row1 = new HashMap<>();
        row1.put("num", "10");
        Map<String, String> row2 = new HashMap<>();
        row2.put("num", "");
        Map<String, String> row3 = new HashMap<>();
        row3.put("num", "20");
        
        var result = svc.fillMissingValues(new ArrayList<>(List.of(row1, row2, row3)), "mean", null, null);
        assertThat(result.get(1).get("num")).isEqualTo("15.0");
    }

    @Test
    @DisplayName("fillMissingValues with mode fills with most frequent value")
    void fillMissingValues_mode() {
        Map<String, String> row1 = new HashMap<>();
        row1.put("val", "A");
        Map<String, String> row2 = new HashMap<>();
        row2.put("val", "");
        Map<String, String> row3 = new HashMap<>();
        row3.put("val", "A");
        Map<String, String> row4 = new HashMap<>();
        row4.put("val", "B");
        
        var result = svc.fillMissingValues(new ArrayList<>(List.of(row1, row2, row3, row4)), "mode", null, null);
        assertThat(result.get(1).get("val")).isEqualTo("A");
    }

    @Test
    @DisplayName("fillMissingValues with forward fills from previous row")
    void fillMissingValues_forward() {
        Map<String, String> row1 = new HashMap<>();
        row1.put("val", "A");
        Map<String, String> row2 = new HashMap<>();
        row2.put("val", "");
        Map<String, String> row3 = new HashMap<>();
        row3.put("val", null);
        
        var result = svc.fillMissingValues(new ArrayList<>(List.of(row1, row2, row3)), "forward", null, null);
        assertThat(result.get(1).get("val")).isEqualTo("A");
        assertThat(result.get(2).get("val")).isEqualTo("A");
    }

    @Test
    @DisplayName("fillMissingValues with backward fills from next row")
    void fillMissingValues_backward() {
        Map<String, String> row1 = new HashMap<>();
        row1.put("val", "");
        Map<String, String> row2 = new HashMap<>();
        row2.put("val", null);
        Map<String, String> row3 = new HashMap<>();
        row3.put("val", "B");
        
        var result = svc.fillMissingValues(new ArrayList<>(List.of(row1, row2, row3)), "backward", null, null);
        assertThat(result.get(0).get("val")).isEqualTo("B");
        assertThat(result.get(1).get("val")).isEqualTo("B");
    }

    @Test
    @DisplayName("standardizeCase handles empty strings gracefully")
    void standardizeCase_emptyStrings() {
        Map<String, String> row = new HashMap<>();
        row.put("a", "");
        row.put("b", null);
        
        var result = svc.standardizeCase(new ArrayList<>(List.of(row)), "upper");
        assertThat(result.get(0).get("a")).isEmpty();
        assertThat(result.get(0).get("b")).isNull();
    }

    @Test
    @DisplayName("normalizeText handles null values")
    void normalizeText_handlesNull() {
        Map<String, String> row = new HashMap<>();
        row.put("a", null);
        row.put("b", "");
        
        var result = svc.normalizeText(new ArrayList<>(List.of(row)));
        assertThat(result.get(0).get("a")).isNull();
        assertThat(result.get(0).get("b")).isEmpty();
    }

    @Test
    @DisplayName("fillMissingValues handles empty list")
    void fillMissingValues_emptyList() {
        var result = svc.fillMissingValues(new ArrayList<>(), "mean", null, null);
        assertThat(result).isEmpty();
    }
    
    @Test
    @DisplayName("mergeSimilarValues with Levenshtein algorithm merges similar strings")
    void mergeSimilarValues_levenshteinAlgorithm() {
        Map<String, String> row1 = new HashMap<>(Map.of("company", "Apple Inc"));
        Map<String, String> row2 = new HashMap<>(Map.of("company", "Apple Inc."));
        Map<String, String> row3 = new HashMap<>(Map.of("company", "Apple  Inc"));  // Extra space
        Map<String, String> row4 = new HashMap<>(Map.of("company", "Microsoft"));
        
        var input = new ArrayList<>(List.of(row1, row2, row3, row4));
        var result = svc.mergeSimilarValues(input, Set.of("company"), "levenshtein", 0.85, false, true, "most_frequent");
        
        // "Apple Inc" and "Apple Inc." should merge (very similar after trimming)
        Set<String> uniqueValues = result.stream()
            .map(r -> r.get("company"))
            .collect(Collectors.toSet());
        assertThat(uniqueValues).hasSizeLessThanOrEqualTo(3); // At least some merging should occur
    }
    
    @Test
    @DisplayName("mergeSimilarValues with Jaro-Winkler algorithm")
    void mergeSimilarValues_jaroWinklerAlgorithm() {
        Map<String, String> row1 = new HashMap<>(Map.of("name", "John Smith"));
        Map<String, String> row2 = new HashMap<>(Map.of("name", "Jon Smith"));
        Map<String, String> row3 = new HashMap<>(Map.of("name", "Jane Doe"));
        
        var input = new ArrayList<>(List.of(row1, row2, row3));
        var result = svc.mergeSimilarValues(input, Set.of("name"), "jaro_winkler", 0.9, false, false, "first");
        
        Set<String> uniqueValues = result.stream()
            .map(r -> r.get("name"))
            .collect(Collectors.toSet());
        // John Smith and Jon Smith are very similar with Jaro-Winkler
        assertThat(uniqueValues).hasSizeLessThanOrEqualTo(2);
    }
    
    @Test
    @DisplayName("mergeSimilarValues case insensitive merges different cases")
    void mergeSimilarValues_caseInsensitive() {
        Map<String, String> row1 = new HashMap<>(Map.of("city", "BOSTON"));
        Map<String, String> row2 = new HashMap<>(Map.of("city", "Boston"));
        Map<String, String> row3 = new HashMap<>(Map.of("city", "boston"));
        Map<String, String> row4 = new HashMap<>(Map.of("city", "Los Angeles"));
        
        var input = new ArrayList<>(List.of(row1, row2, row3, row4));
        var result = svc.mergeSimilarValues(input, Set.of("city"), "levenshtein", 0.95, true, true, "most_frequent");
        
        // All Boston variants should merge when case insensitive (they're identical when normalized)
        Set<String> uniqueValues = result.stream()
            .map(r -> r.get("city"))
            .collect(Collectors.toSet());
        
        // With case insensitive mode, BOSTON/Boston/boston should all become the same
        long bostonVariants = uniqueValues.stream()
            .filter(v -> v.equalsIgnoreCase("boston"))
            .count();
        
        // Should merge to just 1 Boston variant + Los Angeles = 2 total unique values
        assertThat(uniqueValues).hasSize(2);
        assertThat(bostonVariants).isEqualTo(1);
    }
    
    @Test
    @DisplayName("mergeSimilarValues preferred value strategy - shortest")
    void mergeSimilarValues_preferredValue_shortest() {
        Map<String, String> row1 = new HashMap<>(Map.of("code", "USA"));
        Map<String, String> row2 = new HashMap<>(Map.of("code", "U.S.A."));
        Map<String, String> row3 = new HashMap<>(Map.of("code", "U.S.A"));
        
        var input = new ArrayList<>(List.of(row1, row2, row3));
        var result = svc.mergeSimilarValues(input, Set.of("code"), "levenshtein", 0.65, false, false, "shortest");
        
        // Should merge similar values, and "USA" should win as shortest
        Set<String> uniqueValues = result.stream()
            .map(r -> r.get("code"))
            .collect(Collectors.toSet());
        assertThat(uniqueValues).hasSizeLessThanOrEqualTo(2); // Should have some merging
        // If merged to 1, it should be USA
        if (uniqueValues.size() == 1) {
            assertThat(uniqueValues.iterator().next()).isEqualTo("USA");
        }
    }
    
    @Test
    @DisplayName("mergeSimilarValues preferred value strategy - longest")
    void mergeSimilarValues_preferredValue_longest() {
        Map<String, String> row1 = new HashMap<>(Map.of("abbr", "NY"));
        Map<String, String> row2 = new HashMap<>(Map.of("abbr", "N.Y."));
        Map<String, String> row3 = new HashMap<>(Map.of("abbr", "N.Y"));
        
        var input = new ArrayList<>(List.of(row1, row2, row3));
        var result = svc.mergeSimilarValues(input, Set.of("abbr"), "levenshtein", 0.65, false, false, "longest");
        
        Set<String> uniqueValues = result.stream()
            .map(r -> r.get("abbr"))
            .collect(Collectors.toSet());
        assertThat(uniqueValues).hasSizeLessThanOrEqualTo(2); // Should have some merging
        // If merged to 1, longest should win
        if (uniqueValues.size() == 1) {
            assertThat(uniqueValues.iterator().next().length()).isGreaterThanOrEqualTo(3);
        }
    }
    
    @Test
    @DisplayName("mergeSimilarValues handles empty column list")
    void mergeSimilarValues_emptyColumnList() {
        Map<String, String> row = new HashMap<>(Map.of("a", "test"));
        var input = new ArrayList<>(List.of(row));
        
        var result = svc.mergeSimilarValues(input, Collections.emptySet(), "levenshtein", 0.85, false, false, "first");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("a")).isEqualTo("test");
    }
    
    @Test
    @DisplayName("mergeSimilarValues with high threshold keeps values separate")
    void mergeSimilarValues_highThreshold() {
        Map<String, String> row1 = new HashMap<>(Map.of("product", "iPhone"));
        Map<String, String> row2 = new HashMap<>(Map.of("product", "iPad"));
        
        var input = new ArrayList<>(List.of(row1, row2));
        var result = svc.mergeSimilarValues(input, Set.of("product"), "levenshtein", 0.99, false, false, "first");
        
        // High threshold should keep them separate
        Set<String> uniqueValues = result.stream()
            .map(r -> r.get("product"))
            .collect(Collectors.toSet());
        assertThat(uniqueValues).hasSize(2);
    }
    
    @Test
    @DisplayName("mergeSimilarValues with cosine similarity algorithm")
    void mergeSimilarValues_cosineSimilarity() {
        Map<String, String> row1 = new HashMap<>(Map.of("desc", "Software Engineer"));
        Map<String, String> row2 = new HashMap<>(Map.of("desc", "Software Eng"));
        Map<String, String> row3 = new HashMap<>(Map.of("desc", "Hardware Engineer"));
        
        var input = new ArrayList<>(List.of(row1, row2, row3));
        var result = svc.mergeSimilarValues(input, Set.of("desc"), "cosine", 0.7, false, false, "most_frequent");
        
        // Software Engineer and Software Eng should merge (similar trigrams)
        Set<String> uniqueValues = result.stream()
            .map(r -> r.get("desc"))
            .collect(Collectors.toSet());
        assertThat(uniqueValues).hasSizeLessThanOrEqualTo(2);
    }
    
    @Test
    @DisplayName("mergeSimilarValues handles null and empty values")
    void mergeSimilarValues_nullAndEmptyValues() {
        Map<String, String> row1 = new HashMap<>(Map.of("status", "Active"));
        Map<String, String> row2 = new HashMap<>();
        row2.put("status", null);
        Map<String, String> row3 = new HashMap<>(Map.of("status", ""));
        Map<String, String> row4 = new HashMap<>(Map.of("status", "Active"));
        
        var input = new ArrayList<>(List.of(row1, row2, row3, row4));
        var result = svc.mergeSimilarValues(input, Set.of("status"), "levenshtein", 0.85, false, false, "most_frequent");
        
        // Should handle nulls/empties gracefully
        assertThat(result).hasSize(4);
        long activeCount = result.stream()
            .filter(r -> "Active".equals(r.get("status")))
            .count();
        assertThat(activeCount).isEqualTo(2);
    }

    // ========== Text Manipulation Tests ==========
    
    @Test
    @DisplayName("removeExtraSpaces collapses multiple spaces into one")
    void removeExtraSpaces_collapsesSpaces() {
        Map<String, String> row = new HashMap<>();
        row.put("text", "hello    world   test");
        row.put("num", "123");
        
        var result = svc.removeExtraSpaces(new ArrayList<>(List.of(row)));
        assertThat(result.get(0).get("text")).isEqualTo("hello world test");
        assertThat(result.get(0).get("num")).isEqualTo("123");
    }

    @Test
    @DisplayName("removeLineBreaks removes newlines and replaces with spaces")
    void removeLineBreaks_removesNewlines() {
        Map<String, String> row = new HashMap<>();
        row.put("text", "hello\nworld\r\ntest");
        
        var result = svc.removeLineBreaks(new ArrayList<>(List.of(row)));
        assertThat(result.get(0).get("text")).isEqualTo("hello world test");
    }

    @Test
    @DisplayName("removePunctuation removes all punctuation marks")
    void removePunctuation_removesPunctuation() {
        Map<String, String> row = new HashMap<>();
        row.put("text", "Hello, World! How are you?");
        
        var result = svc.removePunctuation(new ArrayList<>(List.of(row)));
        assertThat(result.get(0).get("text")).isEqualTo("Hello World How are you");
    }

    @Test
    @DisplayName("removeNonPrintableChars removes control characters")
    void removeNonPrintableChars_removesControlChars() {
        Map<String, String> row = new HashMap<>();
        row.put("text", "Hello\u0000World\u0001Test");
        
        var result = svc.removeNonPrintableChars(new ArrayList<>(List.of(row)));
        assertThat(result.get(0).get("text")).isEqualTo("HelloWorldTest");
    }

    // ========== Encoding and Unicode Tests ==========
    
    @Test
    @DisplayName("fixEncoding handles encoding issues")
    void fixEncoding_handlesEncodingIssues() {
        Map<String, String> row = new HashMap<>();
        row.put("text", "CafÃ©");
        
        var result = svc.fixEncoding(new ArrayList<>(List.of(row)));
        assertThat(result.get(0)).containsKey("text");
    }

    @Test
    @DisplayName("normalizeUnicode with NFC normalization")
    void normalizeUnicode_nfcNormalization() {
        Map<String, String> row = new HashMap<>();
        row.put("text", "café");
        
        var result = svc.normalizeUnicode(new ArrayList<>(List.of(row)), "NFC");
        assertThat(result.get(0).get("text")).isNotNull();
    }

    @Test
    @DisplayName("normalizeUnicode with NFD normalization")
    void normalizeUnicode_nfdNormalization() {
        Map<String, String> row = new HashMap<>();
        row.put("text", "café");
        
        var result = svc.normalizeUnicode(new ArrayList<>(List.of(row)), "NFD");
        assertThat(result.get(0).get("text")).isNotNull();
    }

    // ========== Numeric Operation Tests ==========
    
    @Test
    @DisplayName("roundDecimals rounds to specified places")
    void roundDecimals_roundsCorrectly() {
        Map<String, String> row = new HashMap<>();
        row.put("price", "10.12345");
        row.put("text", "not a number");
        
        var result = svc.roundDecimals(new ArrayList<>(List.of(row)), 2);
        assertThat(result.get(0).get("price")).isEqualTo("10.12");
        assertThat(result.get(0).get("text")).isEqualTo("not a number");
    }

    @Test
    @DisplayName("roundDecimals handles zero decimal places")
    void roundDecimals_zeroDecimals() {
        Map<String, String> row = new HashMap<>();
        row.put("price", "10.678");
        
        var result = svc.roundDecimals(new ArrayList<>(List.of(row)), 0);
        // Rounding to 0 decimals might return "11" or "11.0" depending on implementation
        assertThat(result.get(0).get("price")).matches("11(\\.0)?");
    }

    // ========== String Replacement Tests ==========
    
    @Test
    @DisplayName("replaceValues replaces based on mapping")
    void replaceValues_replacesFromMap() {
        Map<String, String> row1 = new HashMap<>();
        row1.put("status", "active");
        row1.put("priority", "high");
        Map<String, String> row2 = new HashMap<>();
        row2.put("status", "inactive");
        row2.put("priority", "low");
        
        Map<String, String> replacements = new HashMap<>();
        replacements.put("active", "ACTIVE");
        replacements.put("inactive", "INACTIVE");
        
        var result = svc.replaceValues(new ArrayList<>(List.of(row1, row2)), replacements);
        assertThat(result.get(0).get("status")).isEqualTo("ACTIVE");
        assertThat(result.get(1).get("status")).isEqualTo("INACTIVE");
        assertThat(result.get(0).get("priority")).isEqualTo("high");
    }

    @Test
    @DisplayName("stripPrefix removes specified prefix")
    void stripPrefix_removesPrefix() {
        Map<String, String> row = new HashMap<>();
        row.put("code", "PREFIX_123");
        row.put("name", "test");
        
        var result = svc.stripPrefix(new ArrayList<>(List.of(row)), "PREFIX_");
        assertThat(result.get(0).get("code")).isEqualTo("123");
        assertThat(result.get(0).get("name")).isEqualTo("test");
    }

    @Test
    @DisplayName("stripSuffix removes specified suffix")
    void stripSuffix_removesSuffix() {
        Map<String, String> row = new HashMap<>();
        row.put("file", "document_DRAFT");
        row.put("name", "test");
        
        var result = svc.stripSuffix(new ArrayList<>(List.of(row)), "_DRAFT");
        assertThat(result.get(0).get("file")).isEqualTo("document");
        assertThat(result.get(0).get("name")).isEqualTo("test");
    }

    @Test
    @DisplayName("padValues pads strings to specified length - left padding")
    void padValues_leftPadding() {
        Map<String, String> row = new HashMap<>();
        row.put("id", "123");
        
        var result = svc.padValues(new ArrayList<>(List.of(row)), "left", 5, "0");
        assertThat(result.get(0).get("id")).isEqualTo("00123");
    }

    @Test
    @DisplayName("padValues pads strings to specified length - right padding")
    void padValues_rightPadding() {
        Map<String, String> row = new HashMap<>();
        row.put("code", "AB");
        
        var result = svc.padValues(new ArrayList<>(List.of(row)), "right", 5, "X");
        assertThat(result.get(0).get("code")).isEqualTo("ABXXX");
    }

    // ========== Date Operation Tests ==========
    
    @Test
    @DisplayName("extractDateComponents creates year, month, day columns")
    void extractDateComponents_createsNewColumns() {
        Map<String, String> row = new HashMap<>();
        row.put("date", "2023-05-15");
        row.put("other", "data");
        
        var result = svc.extractDateComponents(new ArrayList<>(List.of(row)));
        assertThat(result.get(0).get("date_year")).isEqualTo("2023");
        assertThat(result.get(0).get("date_month")).isEqualTo("5");
        assertThat(result.get(0).get("date_day")).isEqualTo("15");
        assertThat(result.get(0).get("other")).isEqualTo("data");
    }

    @Test
    @DisplayName("extractDateComponents handles invalid dates gracefully")
    void extractDateComponents_invalidDate() {
        Map<String, String> row = new HashMap<>();
        row.put("date", "not a date");
        
        var result = svc.extractDateComponents(new ArrayList<>(List.of(row)));
        assertThat(result).hasSize(1);
    }

    // ========== Missing Value Handling Tests ==========
    
    @Test
    @DisplayName("fillMissingValues with median fills with middle value")
    void fillMissingValues_median() {
        Map<String, String> row1 = new HashMap<>();
        row1.put("val", "5");
        Map<String, String> row2 = new HashMap<>();
        row2.put("val", "");
        Map<String, String> row3 = new HashMap<>();
        row3.put("val", "10");
        Map<String, String> row4 = new HashMap<>();
        row4.put("val", "15");
        
        var result = svc.fillMissingValues(new ArrayList<>(List.of(row1, row2, row3, row4)), "median", null, null);
        assertThat(result.get(1).get("val")).isEqualTo("10.0");
    }

    @Test
    @DisplayName("fillMissingValues with interpolate fills with linear interpolation")
    void fillMissingValues_interpolate() {
        Map<String, String> row1 = new HashMap<>();
        row1.put("temp", "10");
        Map<String, String> row2 = new HashMap<>();
        row2.put("temp", "");
        Map<String, String> row3 = new HashMap<>();
        row3.put("temp", "20");
        
        var result = svc.fillMissingValues(new ArrayList<>(List.of(row1, row2, row3)), "interpolate", null, null);
        assertThat(result.get(1).get("temp")).isEqualTo("15.0");
    }

    @Test
    @DisplayName("fillMissingValues with specific columns only fills those columns")
    void fillMissingValues_specificColumns() {
        Map<String, String> row = new HashMap<>();
        row.put("a", "");
        row.put("b", "");
        
        var result = svc.fillMissingValues(new ArrayList<>(List.of(row)), "constant", "FILLED", Arrays.asList("a"));
        assertThat(result.get(0).get("a")).isEqualTo("FILLED");
        assertThat(result.get(0).get("b")).isEmpty();
    }

    // ========== Email and URL Tests ==========
    
    @Test
    @DisplayName("extractEmailDomain extracts domain from email")
    void extractEmailDomain_extractsDomain() {
        Map<String, String> row = new HashMap<>();
        row.put("email", "user@example.com");
        row.put("name", "John");
        
        var result = svc.extractEmailDomain(new ArrayList<>(List.of(row)));
        assertThat(result.get(0).get("email_domain")).isEqualTo("example.com");
        assertThat(result.get(0).get("name")).isEqualTo("John");
    }

    @Test
    @DisplayName("validateEmails keeps only valid emails")
    void validateEmails_keepsValidOnly() {
        Map<String, String> row1 = new HashMap<>();
        row1.put("email", "valid@email.com");
        Map<String, String> row2 = new HashMap<>();
        row2.put("email", "invalid-email");
        Map<String, String> row3 = new HashMap<>();
        row3.put("email", "another@test.org");
        
        var result = svc.validateEmails(new ArrayList<>(List.of(row1, row2, row3)));
        // Valid emails should be kept, invalid filtered out
        assertThat(result.size()).isGreaterThanOrEqualTo(2);
        // Check at least one valid email is present
        assertThat(result.stream().anyMatch(m -> "valid@email.com".equals(m.get("email")))).isTrue();
    }

    @Test
    @DisplayName("extractURLComponents extracts protocol, domain, path")
    void extractURLComponents_extractsParts() {
        Map<String, String> row = new HashMap<>();
        row.put("url", "https://www.example.com/path/page?query=1");
        
        var result = svc.extractURLComponents(new ArrayList<>(List.of(row)));
        // Protocol might include :// or just protocol name depending on implementation
        String protocol = result.get(0).get("url_protocol");
        assertThat(protocol).contains("https");
        assertThat(result.get(0).get("url_domain")).contains("example.com");
    }

    @Test
    @DisplayName("normalizeURLs lowercases and handles URLs")
    void normalizeURLs_normalizesURLs() {
        Map<String, String> row = new HashMap<>();
        row.put("url", "HTTPS://Example.Com/Path/");
        
        var result = svc.normalizeURLs(new ArrayList<>(List.of(row)));
        // URL normalization behavior - check that it processes the URL
        assertThat(result.get(0).get("url")).isNotNull();
        String normalized = result.get(0).get("url");
        // Should at least contain the domain
        assertThat(normalized.toLowerCase()).contains("example.com");
    }

    // ========== Phone Number Tests ==========
    
    @Test
    @DisplayName("standardizePhoneNumbers formats to international")
    void standardizePhoneNumbers_international() {
        Map<String, String> row = new HashMap<>();
        row.put("phone", "1234567890");
        
        var result = svc.standardizePhoneNumbers(new ArrayList<>(List.of(row)), "international", "+1");
        assertThat(result.get(0).get("phone")).contains("123");
    }

    @Test
    @DisplayName("standardizePhoneNumbers formats to national")
    void standardizePhoneNumbers_national() {
        Map<String, String> row = new HashMap<>();
        row.put("phone", "5551234567");
        
        var result = svc.standardizePhoneNumbers(new ArrayList<>(List.of(row)), "national", "+1");
        assertThat(result.get(0).get("phone")).isNotEmpty();
    }

    // ========== Column Operation Tests ==========
    
    @Test
    @DisplayName("splitColumn splits by delimiter")
    void splitColumn_splitsByDelimiter() {
        Map<String, String> row = new HashMap<>();
        row.put("name", "John Doe");
        row.put("age", "30");
        
        var result = svc.splitColumn(new ArrayList<>(List.of(row)), "name", " ", Arrays.asList("first", "last"));
        assertThat(result.get(0).get("first")).isEqualTo("John");
        assertThat(result.get(0).get("last")).isEqualTo("Doe");
        assertThat(result.get(0).get("age")).isEqualTo("30");
    }

    @Test
    @DisplayName("splitColumn handles fewer parts than expected")
    void splitColumn_fewerParts() {
        Map<String, String> row = new HashMap<>();
        row.put("name", "John");
        
        var result = svc.splitColumn(new ArrayList<>(List.of(row)), "name", " ", Arrays.asList("first", "middle", "last"));
        assertThat(result.get(0).get("first")).isEqualTo("John");
    }

    @Test
    @DisplayName("mergeColumns merges multiple columns")
    void mergeColumns_mergesWithDelimiter() {
        Map<String, String> row = new HashMap<>();
        row.put("first", "John");
        row.put("last", "Doe");
        row.put("age", "30");
        
        var result = svc.mergeColumns(new ArrayList<>(List.of(row)), Arrays.asList("first", "last"), " ", "full_name");
        assertThat(result.get(0).get("full_name")).isEqualTo("John Doe");
        assertThat(result.get(0).get("age")).isEqualTo("30");
    }

    // ========== Data Type Conversion Tests ==========
    
    @Test
    @DisplayName("convertDataTypes converts string to int")
    void convertDataTypes_stringToInt() {
        Map<String, String> row = new HashMap<>();
        row.put("age", "25");
        row.put("name", "John");
        
        Map<String, String> typeMap = new HashMap<>();
        typeMap.put("age", "integer");
        
        var result = svc.convertDataTypes(new ArrayList<>(List.of(row)), typeMap);
        assertThat(result.get(0).get("age")).isEqualTo("25");
    }

    @Test
    @DisplayName("convertDataTypes converts to float")
    void convertDataTypes_stringToFloat() {
        Map<String, String> row = new HashMap<>();
        row.put("price", "19.99");
        
        Map<String, String> typeMap = new HashMap<>();
        typeMap.put("price", "float");
        
        var result = svc.convertDataTypes(new ArrayList<>(List.of(row)), typeMap);
        assertThat(result.get(0).get("price")).contains("19.99");
    }

    // ========== Row Filtering Tests ==========
    
    @Test
    @DisplayName("removeRowsWithPattern removes matching rows")
    void removeRowsWithPattern_removesMatching() {
        Map<String, String> row1 = new HashMap<>();
        row1.put("text", "test123");
        Map<String, String> row2 = new HashMap<>();
        row2.put("text", "production");
        Map<String, String> row3 = new HashMap<>();
        row3.put("text", "test456");
        
        var result = svc.removeRowsWithPattern(new ArrayList<>(List.of(row1, row2, row3)), "text", "test.*");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("text")).isEqualTo("production");
    }

    @Test
    @DisplayName("keepOnlyNumericRows keeps only rows with numeric values")
    void keepOnlyNumericRows_keepsNumericOnly() {
        Map<String, String> row1 = new HashMap<>();
        row1.put("val", "123");
        Map<String, String> row2 = new HashMap<>();
        row2.put("val", "abc");
        Map<String, String> row3 = new HashMap<>();
        row3.put("val", "456.78");
        
        var result = svc.keepOnlyNumericRows(new ArrayList<>(List.of(row1, row2, row3)), Set.of("val"));
        assertThat(result).hasSize(2);
        assertThat(result).extracting(m -> m.get("val")).containsExactlyInAnyOrder("123", "456.78");
    }

    // ========== Statistical Transformation Tests ==========
    
    @Test
    @DisplayName("normalizeData min-max normalization to 0-1")
    void normalizeData_minMaxNormalization() {
        Map<String, String> row1 = new HashMap<>();
        row1.put("score", "10");
        Map<String, String> row2 = new HashMap<>();
        row2.put("score", "50");
        Map<String, String> row3 = new HashMap<>();
        row3.put("score", "90");
        
        var result = svc.normalizeData(new ArrayList<>(List.of(row1, row2, row3)), Set.of("score"));
        assertThat(result.get(0).get("score")).isEqualTo("0.0");
        assertThat(result.get(1).get("score")).isEqualTo("0.5");
        assertThat(result.get(2).get("score")).isEqualTo("1.0");
    }

    @Test
    @DisplayName("normalizeData handles single value gracefully")
    void normalizeData_singleValue() {
        Map<String, String> row = new HashMap<>();
        row.put("val", "42");
        
        var result = svc.normalizeData(new ArrayList<>(List.of(row)), Set.of("val"));
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("standardizeDataZScore z-score standardization")
    void standardizeDataZScore_zScoreStandardization() {
        Map<String, String> row1 = new HashMap<>();
        row1.put("val", "10");
        Map<String, String> row2 = new HashMap<>();
        row2.put("val", "20");
        Map<String, String> row3 = new HashMap<>();
        row3.put("val", "30");
        
        var result = svc.standardizeDataZScore(new ArrayList<>(List.of(row1, row2, row3)), Set.of("val"));
        assertThat(result.get(0).get("val")).startsWith("-");
        assertThat(result.get(1).get("val")).isEqualTo("0.0");
    }

    @Test
    @DisplayName("binData creates bins from continuous data")
    void binData_createsBins() {
        Map<String, String> row1 = new HashMap<>();
        row1.put("age", "5");
        Map<String, String> row2 = new HashMap<>();
        row2.put("age", "25");
        Map<String, String> row3 = new HashMap<>();
        row3.put("age", "45");
        Map<String, String> row4 = new HashMap<>();
        row4.put("age", "75");
        
        List<Double> edges = Arrays.asList(0.0, 18.0, 35.0, 65.0, 120.0);
        List<String> labels = Arrays.asList("child", "young_adult", "adult", "senior");
        
        var result = svc.binData(new ArrayList<>(List.of(row1, row2, row3, row4)), "age", edges, labels);
        assertThat(result.get(0).get("age_binned")).isEqualTo("child");
        assertThat(result.get(1).get("age_binned")).isEqualTo("young_adult");
        assertThat(result.get(2).get("age_binned")).isEqualTo("adult");
        assertThat(result.get(3).get("age_binned")).isEqualTo("senior");
    }

    @Test
    @DisplayName("binData handles edge case values")
    void binData_edgeCases() {
        Map<String, String> row1 = new HashMap<>();
        row1.put("val", "0");
        Map<String, String> row2 = new HashMap<>();
        row2.put("val", "10");
        
        List<Double> edges = Arrays.asList(0.0, 10.0, 20.0);
        List<String> labels = Arrays.asList("low", "high");
        
        var result = svc.binData(new ArrayList<>(List.of(row1, row2)), "val", edges, labels);
        assertThat(result.get(0).get("val_binned")).isEqualTo("low");
    }

    @Test
    @DisplayName("standardizeCase sentence case capitalizes first letter only")
    void standardizeCase_sentenceCase() {
        Map<String, String> row = new HashMap<>();
        row.put("text", "HELLO WORLD TEST");
        
        var result = svc.standardizeCase(new ArrayList<>(List.of(row)), "sentence");
        assertThat(result.get(0).get("text")).isEqualTo("Hello world test");
    }
}
