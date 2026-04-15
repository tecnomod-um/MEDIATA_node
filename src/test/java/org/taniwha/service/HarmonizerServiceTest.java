package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.taniwha.dto.DataCleaningOptionsDTO;
import org.taniwha.security.FileFilter;
import org.taniwha.service.jobs.HarmonizationProcessingJobs;
import org.taniwha.service.jobs.CleaningProcessingJobs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

class HarmonizerServiceTest {

    private HarmonizerService harmonizerService;
    private HarmonizationProcessingJobs jobs;

    @TempDir
    Path baseDir;

    @BeforeEach
    void setUp() {
        FileFilter fileFilter = mock(FileFilter.class);
        doNothing().when(fileFilter).validate(any(Path.class));

        FileService fileService = new FileService(fileFilter, baseDir.toString());
        DataProcessingService dataProcessingService = new DataProcessingService(fileFilter);
        CleaningProcessingJobs cleaningJobs = new CleaningProcessingJobs();
        DataCleaningService dataCleaningService =
                new DataCleaningService(fileService, dataProcessingService, cleaningJobs);
        jobs = new HarmonizationProcessingJobs();

        harmonizerService = new HarmonizerService(
                dataProcessingService,
                dataCleaningService,
                fileService,
                jobs
        );
    }

    private void makeCsv(String name, String content) throws Exception {
        Path ds = baseDir.resolve("datasets");
        Files.createDirectories(ds);
        Path file = ds.resolve(name);
        Files.writeString(file, content);
    }

    @Test
    void parseFiles_emptyConfig_createsEmptyParsedFile() throws Exception {
        makeCsv("d1.csv", "x;y\n1;2\n");
        String configs = "[]";
        var mappings = Map.of("cfgX", List.of("d1.csv"));

        String result = harmonizerService.parseFiles(configs, mappings, null);
        assertThat(result).isEqualTo("Files processed successfully.");

        Path out = baseDir.resolve("datasets").resolve("parsed_d1.csv");
        assertThat(out).exists();

        String content = Files.readString(out);
        assertThat(content.trim()).isEmpty();
    }

    @Test
    void parseFiles_withMatchingConfig_writesParsedCsv() throws Exception {
        makeCsv("d1.csv", "x;y\n1;2\n");

        String configs = """
            [
              {
                "cfg1": {
                  "fileName":"cfg1",
                  "columns":["x","y"],
                  "groups":[
                    { "column":"x", "values":[] },
                    { "column":"y", "values":[] }
                  ]
                }
              }
            ]
            """;

        var mappings = Map.of("cfg1", List.of("d1.csv"));

        String result = harmonizerService.parseFiles(configs, mappings, null);
        assertThat(result).isEqualTo("Files processed successfully.");

        Path out = baseDir.resolve("datasets").resolve("parsed_d1.csv");
        assertThat(out).exists();

        List<String> lines = Files.readAllLines(out);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isEqualTo("x;y");
        assertThat(lines.get(1)).isEqualTo("1;2");
    }

    @Test
    void parseFiles_withCleaningOptions_removesEmptyAndDuplicates_andStandardizesDates() throws Exception {
        makeCsv("d1.csv", """
            a;b;date
            1;foo;2025/07/11
            1;foo;2025/07/11
            ;;
            2;bar;11-07-2025
            """);

        String configs = """
            [
              {
                "cfg1": {
                  "fileName":"cfg1",
                  "columns":["a","b","date"],
                  "groups":[
                    { "column":"a", "values":[] },
                    { "column":"b", "values":[] },
                    { "column":"date", "values":[] }
                  ]
                }
              }
            ]
            """;

        var mappings = Map.of("cfg1", List.of("d1.csv"));

        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        opts.setRemoveDuplicates(true);
        opts.setRemoveEmptyRows(true);
        opts.setStandardizeDates(true);
        opts.setDateOutputFormat("yyyy-MM-dd");

        String msg = harmonizerService.parseFiles(configs, mappings, opts);
        assertThat(msg).isEqualTo("Files processed successfully.");

        Path out = baseDir.resolve("datasets").resolve("parsed_d1.csv");
        assertThat(out).exists();

        List<String> lines = Files.readAllLines(out);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("a;b;date");
        assertThat(lines.get(1)).isEqualTo("1;foo;2025-07-11");
        assertThat(lines.get(2)).isEqualTo("2;bar;2025-07-11");
    }

    @Test
    void parseFiles_withStandardGroupMapping_appliesGroupColumnLogic() throws Exception {
        makeCsv("d1.csv", "a;b\nfoo;bar\n");

        String configs = """
            [
              {
                "cfg1": {
                  "fileName":"cfg1",
                  "columns":["a","b"],
                  "groups":[
                    { "column":"a", "values":[] },
                    { "column":"b", "values":[] },
                    {
                      "column":"mapped",
                      "values":[
                        {
                          "name":"YES",
                          "mapping":[
                            { "groupColumn":"a", "value":"foo" }
                          ]
                        }
                      ]
                    }
                  ]
                }
              }
            ]
            """;

        var mappings = Map.of("cfg1", List.of("d1.csv"));

        String result = harmonizerService.parseFiles(configs, mappings, null);
        assertThat(result).isEqualTo("Files processed successfully.");

        Path out = baseDir.resolve("datasets").resolve("parsed_d1.csv");
        assertThat(out).exists();

        List<String> lines = Files.readAllLines(out);
        assertThat(lines.get(0)).isEqualTo("a;b;mapped");
        assertThat(lines.get(1)).isEqualTo("foo;bar;YES");
    }

    @Test
    void parseFiles_withCustomOneHotAndRangeMapping_combinesCorrectly() throws Exception {
        makeCsv("d2.csv", """
            age;score;when
            25;88;2025-07-11T10:00:00Z
            42;55;2025-07-12T11:00:00Z
            70;30;2025-07-13T12:00:00Z
            """);

        String configs = """
            [
              {
                "cfg2": {
                  "fileName":"cfg2",
                  "columns":["age","score","when"],
                  "groups":[
                    { "column":"age", "values":[] },
                    { "column":"score", "values":[] },
                    { "column":"when", "values":[] }
                  ]
                }
              },
              {
                "age_group": {
                  "fileName":"custom_mapping",
                  "mappingType":"one-hot",
                  "columns":["age"],
                  "groups":[
                    {
                      "values":[
                        {
                          "name":"MID",
                          "mapping":[
                            {
                              "groupColumn":"age",
                              "value": { "type":"integer", "minValue":40, "maxValue":60 }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
              },
              {
                "high_score": {
                  "fileName":"custom_mapping",
                  "mappingType":"default",
                  "columns":["score"],
                  "groups":[
                    {
                      "values":[
                        {
                          "name":"TOP",
                          "mapping":[
                            { "groupColumn":"score", "value":"88" }
                          ]
                        }
                      ]
                    }
                  ]
                }
              }
            ]
            """;

        var mappings = Map.of("cfg2", List.of("d2.csv"));

        String msg = harmonizerService.parseFiles(configs, mappings, null);
        assertThat(msg).isEqualTo("Files processed successfully.");

        Path out = baseDir.resolve("datasets").resolve("parsed_d2.csv");
        assertThat(out).exists();

        List<String> rows = Files.readAllLines(out);

        assertThat(rows.get(0)).isEqualTo("age;score;when;age_group;high_score");
        assertThat(rows.get(1)).isEqualTo("25;88;2025-07-11T10:00:00Z;0;TOP");
        assertThat(rows.get(2)).isEqualTo("42;55;2025-07-12T11:00:00Z;1;");
        assertThat(rows.get(3)).isEqualTo("70;30;2025-07-13T12:00:00Z;0;");
    }

    // -------------------------------------------------------------------------
    // matchesDeclaredType – via type-marker string value in config
    // -------------------------------------------------------------------------

    @Test
    void parseFiles_typeMarker_integer_passesThroughIntegerValues() throws Exception {
        // In custom-only mode (no regular config for the dataset key), seeding does NOT happen.
        // Type marker "integer" passes through whole-number values and rejects decimals/non-numerics.
        makeCsv("nums.csv", "score\n42\n3.14\nnotanum\n");

        String configs = """
            [
              {
                "custom_mapping": {
                  "fileName":"custom_mapping",
                  "mappingType":"default",
                  "columns":["score"],
                  "groups":[
                    {
                      "values":[
                        {
                          "name":"INT_SCORE",
                          "mapping":[
                            { "groupColumn":"score", "value":"integer" }
                          ]
                        }
                      ]
                    }
                  ]
                }
              }
            ]
            """;

        // Key "noconfig" is not in configs → custom-only mode (no seeding)
        harmonizerService.parseFiles(configs, Map.of("noconfig", List.of("nums.csv")), null);

        Path out = baseDir.resolve("datasets").resolve("parsed_nums.csv");
        List<String> rows = Files.readAllLines(out);

        // Row with 42 (integer) → raw value passthrough "42"
        assertThat(rows.get(1)).isEqualTo("42");
        // Row with 3.14 (decimal, not an integer) → empty
        assertThat(rows.get(2)).isEqualTo("");
        // Row with "notanum" (non-numeric) → empty
        assertThat(rows.get(3)).isEqualTo("");
    }

    @Test
    void parseFiles_typeMarker_double_passesThroughNumericValues() throws Exception {
        makeCsv("floats.csv", "val\n1.5\nword\n");

        String configs = """
            [
              {
                "custom_mapping": {
                  "fileName":"custom_mapping",
                  "mappingType":"default",
                  "columns":["val"],
                  "groups":[
                    {
                      "values":[
                        {
                          "name":"DBL_VAL",
                          "mapping":[
                            { "groupColumn":"val", "value":"double" }
                          ]
                        }
                      ]
                    }
                  ]
                }
              }
            ]
            """;

        // Use a key that's NOT in configs → custom-only mode (no seeding)
        harmonizerService.parseFiles(configs, Map.of("noconfig", List.of("floats.csv")), null);

        Path out = baseDir.resolve("datasets").resolve("parsed_floats.csv");
        List<String> rows = Files.readAllLines(out);

        assertThat(rows.get(1)).isEqualTo("1.5"); // numeric → passthrough
        assertThat(rows.get(2)).isEqualTo("");     // non-numeric → empty
    }

    @Test
    void parseFiles_typeMarker_date_passesThroughDateValues() throws Exception {
        makeCsv("dates.csv", "dt\n2024-03-01\nnotadate\n");

        String configs = """
            [
              {
                "custom_mapping": {
                  "fileName":"custom_mapping",
                  "mappingType":"default",
                  "columns":["dt"],
                  "groups":[
                    {
                      "values":[
                        {
                          "name":"DATE_VAL",
                          "mapping":[
                            { "groupColumn":"dt", "value":"date" }
                          ]
                        }
                      ]
                    }
                  ]
                }
              }
            ]
            """;

        // Use a key that's NOT in configs → custom-only mode
        harmonizerService.parseFiles(configs, Map.of("noconfig", List.of("dates.csv")), null);

        Path out = baseDir.resolve("datasets").resolve("parsed_dates.csv");
        List<String> rows = Files.readAllLines(out);

        assertThat(rows.get(1)).isEqualTo("2024-03-01"); // date → passthrough
        assertThat(rows.get(2)).isEqualTo("");            // non-date → empty
    }

    // -------------------------------------------------------------------------
    // matchRangeOrDate – date range type
    // -------------------------------------------------------------------------

    @Test
    void parseFiles_dateRangeMapping_includesInRangeRows() throws Exception {
        makeCsv("events.csv", "dt\n2023-03-10\n2022-06-15\n2023-11-01\n");

        String configs = """
            [
              {
                "custom_mapping": {
                  "fileName":"custom_mapping",
                  "mappingType":"default",
                  "columns":["dt"],
                  "groups":[
                    {
                      "values":[
                        {
                          "name":"IN_RANGE",
                          "mapping":[
                            {
                              "groupColumn":"dt",
                              "value": { "type":"date", "minValue":"2023-01-01", "maxValue":"2023-12-31" }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
              }
            ]
            """;

        harmonizerService.parseFiles(configs, Map.of("events", List.of("events.csv")), null);

        Path out = baseDir.resolve("datasets").resolve("parsed_events.csv");
        List<String> rows = Files.readAllLines(out);

        // header
        assertThat(rows.get(0)).isEqualTo("custom_mapping");
        // 2023-03-10 is in range → IN_RANGE
        assertThat(rows.get(1)).isEqualTo("IN_RANGE");
        // 2022-06-15 out of range → empty
        assertThat(rows.get(2)).isEqualTo("");
        // 2023-11-01 is in range → IN_RANGE
        assertThat(rows.get(3)).isEqualTo("IN_RANGE");
    }

    // -------------------------------------------------------------------------
    // shouldStandardizeNumeric – branches
    // -------------------------------------------------------------------------

    @Test
    void parseFiles_withStandardizeNumeric_normalizesValues() throws Exception {
        // Tests shouldStandardizeNumeric returning true → calls standardizeNumericInPlace
        makeCsv("nums2.csv", "price\n10.5\n20.0\n");

        String configs = """
            [
              {
                "cfg_num": {
                  "fileName":"cfg_num",
                  "columns":["price"],
                  "groups":[
                    { "column":"price", "values":[] }
                  ]
                }
              }
            ]
            """;

        DataCleaningOptionsDTO cleanOpts = new DataCleaningOptionsDTO();
        cleanOpts.setStandardizeNumeric(true);
        cleanOpts.setNumericMode("double");
        cleanOpts.setNumericColumns(List.of("cfg_num:::price"));

        harmonizerService.parseFiles(configs, Map.of("cfg_num", List.of("nums2.csv")), cleanOpts);

        Path out = baseDir.resolve("datasets").resolve("parsed_nums2.csv");
        assertThat(out).exists();
        List<String> rows = Files.readAllLines(out);
        // rows[1] and rows[2] contain the price values (standardized to double format)
        assertThat(rows).hasSizeGreaterThan(1);
    }

    @Test
    void parseFiles_withStandardizeNumeric_falseFlag_noNormalization() throws Exception {
        makeCsv("nums3.csv", "price\n10.5\n");

        String configs = """
            [
              {
                "cfg_num2": {
                  "fileName":"cfg_num2",
                  "columns":["price"],
                  "groups":[
                    { "column":"price", "values":[] }
                  ]
                }
              }
            ]
            """;

        DataCleaningOptionsDTO cleanOpts = new DataCleaningOptionsDTO();
        cleanOpts.setStandardizeNumeric(false); // ← key: shouldStandardizeNumeric returns false

        // Should still process without error
        String msg = harmonizerService.parseFiles(configs, Map.of("cfg_num2", List.of("nums3.csv")), cleanOpts);
        assertThat(msg).isEqualTo("Files processed successfully.");
    }

    @Test
    void parseFiles_withStandardizeNumeric_emptyColumns_noNormalization() throws Exception {
        makeCsv("nums4.csv", "price\n10.5\n");

        String configs = """
            [
              {
                "cfg_num3": {
                  "fileName":"cfg_num3",
                  "columns":["price"],
                  "groups":[
                    { "column":"price", "values":[] }
                  ]
                }
              }
            ]
            """;

        DataCleaningOptionsDTO cleanOpts = new DataCleaningOptionsDTO();
        cleanOpts.setStandardizeNumeric(true);
        cleanOpts.setNumericMode("double");
        cleanOpts.setNumericColumns(List.of()); // ← empty → shouldStandardizeNumeric returns false

        String msg = harmonizerService.parseFiles(configs, Map.of("cfg_num3", List.of("nums4.csv")), cleanOpts);
        assertThat(msg).isEqualTo("Files processed successfully.");
    }

    // =========================================================================
    // parseFilesWithProgress – covers the async path and lambda chains
    // =========================================================================

    @Test
    void parseFilesWithProgress_emptyConfig_completesSuccessfully() throws Exception {
        makeCsv("prog1.csv", "col1;col2\nA;1\nB;2\n");

        String configs = "[]";
        Map<String, List<String>> mappings = Map.of("prog1.csv", List.of("prog1.csv"));

        String result = harmonizerService.parseFilesWithProgress("job-p1", configs, mappings, null);
        assertThat(result).contains("successfully");
    }

    @Test
    void parseFilesWithProgress_withSimpleConfig_writesOutputFile() throws Exception {
        makeCsv("prog2.csv", "color;size\nred;10\nblue;20\n");

        String configs = """
            [
              {
                "prog2.csv": {
                  "fileName":"prog2.csv",
                  "columns":["color"],
                  "groups":[
                    {
                      "column":"color",
                      "values":[
                        {"value":"red",  "mapping":[{"name":"COLOR_RED",  "type":"binary"}]},
                        {"value":"blue", "mapping":[{"name":"COLOR_BLUE", "type":"binary"}]}
                      ]
                    }
                  ]
                }
              }
            ]
            """;

        Map<String, List<String>> mappings = Map.of("prog2.csv", List.of("prog2.csv"));

        String result = harmonizerService.parseFilesWithProgress("job-p2", configs, mappings, null);
        assertThat(result).contains("successfully");
    }

    @Test
    void parseFilesWithProgress_missingFile_skipsAndCompletes() throws Exception {
        // Do NOT create the file → should skip with a warning and complete
        String configs = "[]";
        Map<String, List<String>> mappings = Map.of("missing.csv", List.of("missing.csv"));

        String result = harmonizerService.parseFilesWithProgress("job-p3", configs, mappings, null);
        assertThat(result).contains("successfully");
    }

    @Test
    void parseFilesWithProgress_withCleaningOpts_appliesCleaningAndCompletes() throws Exception {
        makeCsv("prog4.csv", "name;score\n  Alice  ;10\n  Alice  ;20\n");

        String configs = "[]";
        Map<String, List<String>> mappings = Map.of("prog4.csv", List.of("prog4.csv"));

        DataCleaningOptionsDTO cleanOpts = new DataCleaningOptionsDTO();
        cleanOpts.setTrimWhitespace(true);
        cleanOpts.setRemoveDuplicates(false);

        String result = harmonizerService.parseFilesWithProgress("job-p4", configs, mappings, cleanOpts);
        assertThat(result).contains("successfully");
    }

    @Test
    void parseFilesWithProgress_emptyMappings_completesSuccessfully() throws Exception {
        String configs = "[]";
        Map<String, List<String>> mappings = Map.of();

        String result = harmonizerService.parseFilesWithProgress("job-p5", configs, mappings, null);
        assertThat(result).contains("successfully");
    }

    @Test
    void parseFilesWithProgress_withCustomOneHotAndDefaultMappings_writesMappedDataset() throws Exception {
        makeCsv("d_prog.csv", """
            age;score;when
            25;88;2025-07-11T10:00:00Z
            42;55;2025-07-12T11:00:00Z
            """);

        String configs = """
            [
              {
                "cfg_prog": {
                  "fileName":"cfg_prog",
                  "columns":["age","score","when"],
                  "groups":[
                    { "column":"age", "values":[] },
                    { "column":"score", "values":[] },
                    { "column":"when", "values":[] }
                  ]
                }
              },
              {
                "age_group": {
                  "fileName":"custom_mapping",
                  "mappingType":"one-hot",
                  "columns":["age"],
                  "groups":[
                    {
                      "values":[
                        {
                          "name":"MID",
                          "mapping":[
                            {
                              "groupColumn":"age",
                              "value": { "type":"integer", "minValue":40, "maxValue":60 }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
              },
              {
                "high_score": {
                  "fileName":"custom_mapping",
                  "mappingType":"default",
                  "columns":["score"],
                  "groups":[
                    {
                      "values":[
                        {
                          "name":"TOP",
                          "mapping":[
                            { "groupColumn":"score", "value":"88" }
                          ]
                        }
                      ]
                    }
                  ]
                }
              }
            ]
            """;

        String msg = harmonizerService.parseFilesWithProgress(
                "job-p6",
                configs,
                Map.of("cfg_prog", List.of("d_prog.csv")),
                null
        );

        assertThat(msg).isEqualTo("Files processed successfully.");
        Path out = baseDir.resolve("datasets").resolve("parsed_d_prog.csv");
        assertThat(out).exists();
        List<String> rows = Files.readAllLines(out);
        assertThat(rows.get(0)).isEqualTo("age;score;when;age_group;high_score");
        assertThat(rows).contains("25;88;2025-07-11T10:00:00Z;0;TOP");
        assertThat(rows).contains("42;55;2025-07-12T11:00:00Z;1;");
    }

    @Test
    void startParseJob_success_updatesJobToDone() throws Exception {
        makeCsv("async_ok.csv", "a;b\n1;2\n");
        String jobId = jobs.createJob();

        harmonizerService.startParseJob(
                jobId,
                "[]",
                Map.of("async_ok.csv", List.of("async_ok.csv")),
                null
        );

        for (int i = 0; i < 60; i++) {
            var st = jobs.getJob(jobId);
            if (st != null && st.getState() != org.taniwha.dto.HarmonizationStatusDTO.State.RUNNING) break;
            Thread.sleep(50);
        }

        var state = jobs.getJob(jobId);
        assertThat(state).isNotNull();
        assertThat(state.getState()).isEqualTo(org.taniwha.dto.HarmonizationStatusDTO.State.DONE);
        assertThat(state.getPercent().get()).isEqualTo(100);
    }

    @Test
    void startParseJob_invalidJson_updatesJobToError() throws Exception {
        String jobId = jobs.createJob();

        harmonizerService.startParseJob(
                jobId,
                "{not-json}",
                Map.of("bad", List.of("missing.csv")),
                null
        );

        for (int i = 0; i < 60; i++) {
            var st = jobs.getJob(jobId);
            if (st != null && st.getState() == org.taniwha.dto.HarmonizationStatusDTO.State.ERROR) break;
            Thread.sleep(50);
        }

        var state = jobs.getJob(jobId);
        assertThat(state).isNotNull();
        assertThat(state.getState()).isEqualTo(org.taniwha.dto.HarmonizationStatusDTO.State.ERROR);
        assertThat(state.getMessage()).contains("Error processing files");
    }
}
