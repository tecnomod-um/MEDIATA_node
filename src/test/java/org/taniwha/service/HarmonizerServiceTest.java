package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.taniwha.dto.DataCleaningOptionsDTO;
import org.taniwha.security.FileFilter;

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

    @TempDir
    Path baseDir;

    @BeforeEach
    void setUp() {
        FileFilter fileFilter = mock(FileFilter.class);
        doNothing().when(fileFilter).validate(any(Path.class));

        FileService fileService = new FileService(fileFilter, baseDir.toString());
        DataProcessingService dataProcessingService = new DataProcessingService(fileFilter);
        DataCleaningService dataCleaningService = new DataCleaningService();
        harmonizerService = new HarmonizerService(dataProcessingService, dataCleaningService, fileService);
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

        // With no configs, outputHeaders is empty, so file should be empty (or just a newline)
        String content = Files.readString(out);
        assertThat(content.trim()).isEmpty();
    }

    @Test
    void parseFiles_withMatchingConfig_writesParsedCsv() throws Exception {
        makeCsv("d1.csv", "x;y\n1;2\n");

        // IMPORTANT: headers come from groups[].column now
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

        // IMPORTANT: headers come from groups[].column now
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

        // IMPORTANT: include passthrough headers via groups
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

        // IMPORTANT:
        // - cfg2 must define headers via groups (age/score/when passthrough)
        // - range mapping must be in mapping.value as an object {type,minValue,maxValue}
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
        // age_group "one-hot": hit -> 1 else 0
        assertThat(rows.get(1)).isEqualTo("25;88;2025-07-11T10:00:00Z;0;TOP");
        assertThat(rows.get(2)).isEqualTo("42;55;2025-07-12T11:00:00Z;1;");
        assertThat(rows.get(3)).isEqualTo("70;30;2025-07-13T12:00:00Z;0;");
    }

}
