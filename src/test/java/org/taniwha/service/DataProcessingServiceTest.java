package org.taniwha.service;


import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.taniwha.security.FileFilter;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;

class DataProcessingServiceTest {

    @Mock
    private FileFilter fileFilter;

    @InjectMocks
    private DataProcessingService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void streamRows_csvPath_readsAllRows() throws Exception {
        Path csv = Files.createTempFile("dpstest", ".csv");
        Files.writeString(csv, "c1,c2\nv1, w1\nv2, w2\n");
        doNothing().when(fileFilter).validate(csv);

        List<Map<String, String>> rows = new ArrayList<>();
        service.streamRows(csv, rows::add);

        assertThat(rows)
                .hasSize(2)
                .extracting(m -> m.get("c1"))
                .containsExactly("v1", "v2");
        assertThat(rows)
                .extracting(m -> m.get("c2"))
                .containsExactly("w1", "w2");
    }

    @Test
    void streamRows_csvMultipart_readsAllRowsWithSemicolon() throws Exception {
        String data = "a;b\n1;2\n3;4\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.csv", "text/csv", data.getBytes());
        doNothing().when(fileFilter).validate(file);

        List<Map<String, String>> rows = new ArrayList<>();
        service.streamRows(file, rows::add);

        assertThat(rows)
                .hasSize(2)
                .extracting(m -> m.get("a"))
                .containsExactly("1", "3");
        assertThat(rows)
                .extracting(m -> m.get("b"))
                .containsExactly("2", "4");
    }

    @Test
    void extractDataFromPath_csv_returnsAllRecords() throws Exception {
        Path csv = Files.createTempFile("dpstest2", ".csv");
        Files.writeString(csv, "h1,h2\nA,B\nC,D\n");
        doNothing().when(fileFilter).validate(csv);

        var records = service.extractDataFromPath(csv);
        assertThat(records)
                .hasSize(2)
                .extracting(r -> r.get("h1"))
                .containsExactly("A", "C");
        assertThat(records)
                .extracting(r -> r.get("h2"))
                .containsExactly("B", "D");
    }

    @Test
    void extractData_multipartCsv_returnsAllRecords() throws Exception {
        String content = "x,y\nfoo,bar\nbaz,qux\n";
        MockMultipartFile mf = new MockMultipartFile(
                "f", "data.csv", "text/plain", content.getBytes());
        doNothing().when(fileFilter).validate(mf);

        var recs = service.extractData(mf);
        assertThat(recs).hasSize(2);
        assertThat(recs.get(0)).containsEntry("x", "foo").containsEntry("y", "bar");
        assertThat(recs.get(1)).containsEntry("x", "baz").containsEntry("y", "qux");
    }

    @Test
    void extractFilteredDataFromPath_csv_filtersCorrectly() throws Exception {
        Path csv = Files.createTempFile("dpstest3", ".csv");
        Files.writeString(csv, "c,d\n10,foo\n20,bar\n30,foo\n");
        doNothing().when(fileFilter).validate(csv);
        Map<String, Object> filters = Map.of(
                "operator", "AND",
                "conditions", Map.of("d", "foo")
        );

        var recs = service.extractFilteredDataFromPath(csv, filters);
        assertThat(recs)
                .hasSize(2)
                .allSatisfy(r -> assertThat(r.get("d")).isEqualTo("foo"))
                .extracting(r -> r.get("c"))
                .containsExactly("10", "30");
    }

    @Test
    void streamRows_unsupportedExtension_throws() {
        assertThatThrownBy(() -> {
            Path other = Files.createTempFile("dpstest4", ".xxx");
            doNothing().when(fileFilter).validate(other);
            service.streamRows(other, r -> {
            });
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported file type");
    }

    @Test
    void streamRows_xlsx_readsAllRows() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet();
        Row hdr = sheet.createRow(0);
        hdr.createCell(0).setCellValue("c");
        hdr.createCell(1).setCellValue("d");
        Row r1 = sheet.createRow(1);
        r1.createCell(0).setCellValue("10");
        r1.createCell(1).setCellValue("foo");
        Row r2 = sheet.createRow(2);
        r2.createCell(0).setCellValue("20");
        r2.createCell(1).setCellValue("bar");
        Path tmp = Files.createTempFile("dpstest", ".xlsx");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            wb.write(out);
        }
        wb.close();

        doNothing().when(fileFilter).validate(tmp);
        List<Map<String, String>> rows = new ArrayList<>();
        service.streamRows(tmp, rows::add);

        assertThat(rows).hasSize(2)
                .extracting(m -> m.get("c")).containsExactly("10", "20");
        assertThat(rows).extracting(m -> m.get("d")).containsExactly("foo", "bar");
    }

    @Test
    void streamRows_multipartXlsx_readsRows() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sht = wb.createSheet();
        Row h = sht.createRow(0);
        h.createCell(0).setCellValue("x");
        Row d0 = sht.createRow(1);
        d0.createCell(0).setCellValue("A");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        wb.write(bos);
        wb.close();

        MockMultipartFile mf = new MockMultipartFile(
                "file", "data.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                bos.toByteArray()
        );
        doNothing().when(fileFilter).validate(mf);
        List<Map<String, String>> rows = new ArrayList<>();
        service.streamRows(mf, rows::add);

        assertThat(rows).hasSize(1)
                .first().extracting(m -> m.get("x")).isEqualTo("A");
    }

    @Test
    void streamRows_csvPath_withTabDelimiter() throws Exception {
        Path csv = Files.createTempFile("dpstestTab", ".csv");
        Files.writeString(csv, "t1\tt2\nv1\tv2\n");
        doNothing().when(fileFilter).validate(csv);

        List<Map<String, String>> rows = new ArrayList<>();
        service.streamRows(csv, rows::add);
        assertThat(rows).hasSize(1);
        Map<String, String> first = rows.get(0);
        assertThat(first).containsEntry("t1", "v1").containsEntry("t2", "v2");
    }


    @Test
    void extractDataFromPath_xlsx_returnsAllRecords() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet();
        Row hdr = sheet.createRow(0);
        hdr.createCell(0).setCellValue("h1");
        hdr.createCell(1).setCellValue("h2");
        Row d1 = sheet.createRow(1);
        d1.createCell(0).setCellValue("A");
        d1.createCell(1).setCellValue("B");
        Row d2 = sheet.createRow(2);
        d2.createCell(0).setCellValue("C");
        d2.createCell(1).setCellValue("D");
        Path xlsx = Files.createTempFile("dpstest5", ".xlsx");
        try (OutputStream o = Files.newOutputStream(xlsx)) {
            wb.write(o);
        }
        wb.close();
        doNothing().when(fileFilter).validate(xlsx);
        var recs = service.extractDataFromPath(xlsx);

        assertThat(recs).hasSize(2)
                .extracting(r -> r.get("h1")).containsExactly("A", "C");
        assertThat(recs).extracting(r -> r.get("h2")).containsExactly("B", "D");
    }

    @Test
    void extractFilteredDataFromPath_xlsx_filtersCorrectly() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sh = wb.createSheet();
        Row h = sh.createRow(0);
        h.createCell(0).setCellValue("c");
        h.createCell(1).setCellValue("d");
        Row r1 = sh.createRow(1);
        r1.createCell(0).setCellValue("10");
        r1.createCell(1).setCellValue("foo");
        Row r2 = sh.createRow(2);
        r2.createCell(0).setCellValue("20");
        r2.createCell(1).setCellValue("bar");
        Row r3 = sh.createRow(3);
        r3.createCell(0).setCellValue("30");
        r3.createCell(1).setCellValue("foo");
        Path xlsx = Files.createTempFile("dpstest6", ".xlsx");
        try (OutputStream o = Files.newOutputStream(xlsx)) {
            wb.write(o);
        }
        wb.close();
        doNothing().when(fileFilter).validate(xlsx);
        Map<String, Object> filters = Map.of(
                "operator", "AND",
                "conditions", Map.of("d", "foo")
        );
        var matches = service.extractFilteredDataFromPath(xlsx, filters);

        assertThat(matches).hasSize(2)
                .allSatisfy(m -> assertThat(m.get("d")).isEqualTo("foo"))
                .extracting(m -> m.get("c")).containsExactly("10", "30");
    }

    @Test
    void extractFilteredDataFromPath_csv_orOperator_listFilter() throws Exception {
        Path csv = Files.createTempFile("dpstest7", ".csv");
        Files.writeString(csv, "c,d\n1,a\n2,b\n3,a\n");
        doNothing().when(fileFilter).validate(csv);
        Map<String, Object> filters = new HashMap<>();
        filters.put("operator", "OR");
        filters.put("conditions", Map.of("d", List.of("a")));
        var out = service.extractFilteredDataFromPath(csv, filters);

        assertThat(out).hasSize(2)
                .extracting(r -> r.get("c")).containsExactly("1", "3");
    }

    @Test
    void extractFilteredDataFromPath_csv_complexCondition_and_filtersBetween() throws Exception {
        Path csv = Files.createTempFile("dpstest8", ".csv");
        Files.writeString(csv, "c\n10\n20\n30\n");
        doNothing().when(fileFilter).validate(csv);
        Map<String, Object> cond = new HashMap<>();
        cond.put("conditions", List.of(
                Map.of("type", "greater", "filterType", "continuous", "value", "15"),
                Map.of("type", "less", "filterType", "continuous", "value", "25")
        ));
        cond.put("operators", List.of("AND"));
        Map<String, Object> filters = Map.of(
                "operator", "AND",
                "conditions", Map.of("c", cond)
        );
        var out = service.extractFilteredDataFromPath(csv, filters);

        assertThat(out).hasSize(1)
                .first().extracting(m -> m.get("c")).isEqualTo("20");
    }

    @Test
    void extractFilteredDataFromPath_csv_invalidCriteria_throws() throws Exception {
        Path csv = Files.createTempFile("dpstest9", ".csv");
        Files.writeString(csv, "a,b\n1,2\n");
        doNothing().when(fileFilter).validate(csv);

        Map<String, Object> filters = Map.of(
                "operator", "AND",
                "conditions", Map.of("a", 123)  // invalid criteria type
        );

        assertThatThrownBy(() ->
                service.extractFilteredDataFromPath(csv, filters)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid filter criteria format");
    }

    @Test
    @DisplayName("extractFilteredDataFromPath – filter by explicit list of numeric strings")
    void extractFilteredDataFromPath_csv_numericListFilter() throws Exception {
        Path csv = Files.createTempFile("dps_num_list", ".csv");
        Files.writeString(csv, "n\n1.5\n2.3\n4.7\n");
        doNothing().when(fileFilter).validate(csv);

        Map<String, Object> filters = Map.of(
                "operator", "AND",
                "conditions", Map.of("n", List.of("2.3", "4.7"))
        );

        var out = service.extractFilteredDataFromPath(csv, filters);

        assertThat(out).hasSize(2)
                .extracting(m -> m.get("n"))
                .containsExactly("2.3", "4.7");
    }


    @Test
    @DisplayName("extractFilteredDataFromPath – header has file suffix, filter still matches")
    void extractFilteredDataFromPath_csv_stripFileSuffix() throws Exception {
        Path csv = Files.createTempFile("dps_suffix", ".csv");
        Files.writeString(csv, "val\nfoo\nbar\n");
        doNothing().when(fileFilter).validate(csv);
        Map<String, Object> filters = Map.of(
                "operator", "AND",
                "conditions", Map.of("val (myFile.csv)", "foo")
        );

        var out = service.extractFilteredDataFromPath(csv, filters);
        assertThat(out).hasSize(1)
                .first()
                .extracting(m -> m.get("val"))
                .isEqualTo("foo");
    }

    @Test
    @DisplayName("extractFilteredDataFromPath – invalid nested criteria ⇒ IllegalArgumentException")
    void extractFilteredDataFromPath_csv_invalidNestedCriteria_throws() throws Exception {
        Path csv = Files.createTempFile("dps_bad_nested", ".csv");
        Files.writeString(csv, "c\n10\n");
        doNothing().when(fileFilter).validate(csv);
        Map<String, Object> invalidCond = Map.of(
                "conditions", List.of(
                        Map.of("type", "greater", "value", "5")
                ),
                "operators", List.of()
        );
        Map<String, Object> filters = Map.of(
                "operator", "AND",
                "conditions", Map.of("c", invalidCond)
        );

        assertThatThrownBy(() -> service.extractFilteredDataFromPath(csv, filters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing type or filterType");
    }

    @Test
    @DisplayName("extractFilteredDataFromPath – compareValues parse failure handled gracefully")
    void extractFilteredDataFromPath_csv_nonNumericCompare_returnsEmpty() throws Exception {
        Path csv = Files.createTempFile("dps_bad_num", ".csv");
        Files.writeString(csv, "n\n10\n");
        doNothing().when(fileFilter).validate(csv);
        Map<String, Object> filters = Map.of(
                "operator", "AND",
                "conditions", Map.of(
                        "n", Map.of(
                                "type", "equal",
                                "filterType", "continuous",
                                "value", "not-a-number"
                        )
                )
        );

        var out = service.extractFilteredDataFromPath(csv, filters);
        assertThat(out).isEmpty();
    }
}
