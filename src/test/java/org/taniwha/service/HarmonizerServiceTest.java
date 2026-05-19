package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.taniwha.dto.DataCleaningOptionsDTO;
import org.taniwha.dto.HarmonizationStatusDTO;
import org.taniwha.dto.mapping.MappingDefinitionDTO;
import org.taniwha.dto.mapping.MappingInputDTO;
import org.taniwha.dto.mapping.MappingMatcherDTO;
import org.taniwha.dto.mapping.MappingMetadataDTO;
import org.taniwha.dto.mapping.MappingRuleDTO;
import org.taniwha.dto.mapping.MappingSpecDTO;
import org.taniwha.dto.mapping.OneHotOutputDTO;
import org.taniwha.dto.mapping.RuleResultDTO;
import org.taniwha.security.FileFilter;
import org.taniwha.service.jobs.CleaningProcessingJobs;
import org.taniwha.service.jobs.HarmonizationProcessingJobs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

class HarmonizerServiceTest {

    private HarmonizerService harmonizerService;
    private HarmonizationProcessingJobs jobs;
    private FileService fileService;

    @TempDir
    Path baseDir;

    @BeforeEach
    void setUp() {
        FileFilter fileFilter = mock(FileFilter.class);
        doNothing().when(fileFilter).validate(any(Path.class));

        fileService = new FileService(fileFilter, baseDir.toString());
        DataProcessingService dataProcessingService = new DataProcessingService(fileFilter);
        CleaningProcessingJobs cleaningJobs = new CleaningProcessingJobs();
        DataCleaningService dataCleaningService =
                new DataCleaningService(fileService, dataProcessingService, cleaningJobs);
        jobs = new HarmonizationProcessingJobs();
        MappingSpecAdapter mappingSpecAdapter = new MappingSpecAdapter();

        harmonizerService = new HarmonizerService(
                dataProcessingService,
                dataCleaningService,
                fileService,
                jobs,
                mappingSpecAdapter
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
        MappingSpecDTO spec = new MappingSpecDTO();
        spec.setSpecVersion("1.0.0");
        spec.setMappings(List.of());

        var mappings = Map.of("cfgX", List.of("d1.csv"));

        String result = harmonizerService.parseFiles(spec, mappings, null);
        assertThat(result).isEqualTo("Files processed successfully.");

        Path out = baseDir.resolve("datasets").resolve("parsed_d1.csv");
        assertThat(out).exists();

        String content = Files.readString(out);
        assertThat(content.trim()).isEmpty();
    }

    @Test
    void parseFiles_withMatchingConfig_writesParsedCsv() throws Exception {
        makeCsv("d1.csv", "x;y\n1;2\n");

        MappingSpecDTO spec = new MappingSpecDTO();
        spec.setSpecVersion("1.0.0");
        spec.setMappings(List.of());

        var mappings = Map.of("cfg1", List.of("d1.csv"));

        String result = harmonizerService.parseFiles(spec, mappings, null);
        assertThat(result).isEqualTo("Files processed successfully.");

        Path out = baseDir.resolve("datasets").resolve("parsed_d1.csv");
        assertThat(out).exists();

        List<String> lines = Files.readAllLines(out);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).isEqualTo("");
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

        MappingSpecDTO spec = standardCopySchemaSpec("a", "b", "date");
        var mappings = Map.of("cfg1", List.of("d1.csv"));

        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        opts.setRemoveDuplicates(true);
        opts.setRemoveEmptyRows(true);
        opts.setStandardizeDates(true);
        opts.setDateOutputFormat("yyyy-MM-dd");

        String msg = harmonizerService.parseFiles(spec, mappings, opts);
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

        MappingSpecDTO spec = standardMappedValueSpec(
                "mapped",
                "n1::cfg1",
                "a",
                "foo",
                "YES"
        );

        var mappings = Map.of("cfg1", List.of("d1.csv"));

        String result = harmonizerService.parseFiles(spec, mappings, null);
        assertThat(result).isEqualTo("Files processed successfully.");

        Path out = baseDir.resolve("datasets").resolve("parsed_d1.csv");
        assertThat(out).exists();

        List<String> lines = Files.readAllLines(out);
        assertThat(lines.get(0)).isEqualTo("mapped");
        assertThat(lines.get(1)).isEqualTo("YES");
    }

    @Test
    void parseFiles_withCustomOneHotAndRangeMapping_combinesCorrectly() throws Exception {
        makeCsv("d2.csv", """
            age;score;when
            25;88;2025-07-11T10:00:00Z
            42;55;2025-07-12T11:00:00Z
            70;30;2025-07-13T12:00:00Z
            """);

        MappingSpecDTO spec = new MappingSpecDTO();
        spec.setSpecVersion("1.0.0");
        spec.setMappings(List.of(
                oneHotRangeSpec("age_group", "n1::cfg2", "age", "integer", 40, 60),
                standardMappedValueSpec("high_score", "n1::cfg2", "score", "88", "TOP").getMappings().get(0)
        ));

        var mappings = Map.of("cfg2", List.of("d2.csv"));

        String msg = harmonizerService.parseFiles(spec, mappings, null);
        assertThat(msg).isEqualTo("Files processed successfully.");

        Path out = baseDir.resolve("datasets").resolve("parsed_d2.csv");
        assertThat(out).exists();

        List<String> rows = Files.readAllLines(out);

        assertThat(rows.get(0)).isEqualTo("age_group;high_score");
        assertThat(rows.get(1)).isEqualTo("0;TOP");
        assertThat(rows.get(2)).isEqualTo("1;");
        assertThat(rows.get(3)).isEqualTo("0;");
    }

    @Test
    void parseFiles_inheritsShareabilityForGeneratedParsedDataset() throws Exception {
        makeCsv("samplefile.csv", "a;b\n1;2\n");
        fileService.setDatasetFamilyDownloadable("samplefile.csv", true);

        MappingSpecDTO spec = standardCopySchemaSpec("a", "b");
        var mappings = Map.of("cfg1", List.of("samplefile.csv"));

        String result = harmonizerService.parseFiles(spec, mappings, null);
        assertThat(result).isEqualTo("Files processed successfully.");
        assertThat(fileService.isDatasetDownloadAllowed("parsed_samplefile.csv")).isTrue();
        assertThat(fileService.resolveSharedDatasetFilePath("parsed_samplefile.csv")).exists();
    }

    @Test
    void parseFiles_customMappingsOnlyIncludeTargetsThatReferenceCurrentSourceFile() throws Exception {
        makeCsv("d4d.csv", """
            Barthel Change;Age
            6;68
            8;62
            """);

        MappingSpecDTO spec = new MappingSpecDTO();
        spec.setSpecVersion("1.0.0");
        spec.setMappings(List.of(
                customTypedPassthroughSpec("BarthelTotalChange", "n1::cfgD4D", "Barthel Change", "integer"),
                customExactMappingSpec("Feeding", "n1::cfgOther", "Feeding source", "independent", "Independent")
        ));

        var mappings = Map.of("cfgD4D", List.of("d4d.csv"));

        String result = harmonizerService.parseFiles(spec, mappings, null);
        assertThat(result).isEqualTo("Files processed successfully.");

        Path out = baseDir.resolve("datasets").resolve("parsed_d4d.csv");
        assertThat(out).exists();

        List<String> lines = Files.readAllLines(out);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("BarthelTotalChange");
        assertThat(lines.get(1)).isEqualTo("6");
        assertThat(lines.get(2)).isEqualTo("8");
    }

    private MappingSpecDTO standardCopySchemaSpec(String... fields) {
        List<MappingDefinitionDTO> defs = java.util.Arrays.stream(fields)
                .map(field -> {
                    MappingInputDTO input = new MappingInputDTO();
                    input.setSourceId("n1::cfg1");
                    input.setColumn(field);

                    MappingDefinitionDTO def = new MappingDefinitionDTO();
                    def.setId("copy-" + field);
                    def.setTargetField(field);
                    def.setMappingType("standard");
                    def.setInputs(List.of(input));
                    def.setRules(List.of());
                    def.setMetadata(new MappingMetadataDTO());
                    return def;
                })
                .toList();

        MappingSpecDTO spec = new MappingSpecDTO();
        spec.setSpecVersion("1.0.0");
        spec.setMappings(defs);
        return spec;
    }

    private MappingSpecDTO standardMappedValueSpec(String targetField,
                                                   String sourceId,
                                                   String sourceColumn,
                                                   String matchValue,
                                                   String outputValue) {
        MappingMatcherDTO matcher = exactMatcher(sourceId, sourceColumn, matchValue);

        MappingRuleDTO rule = new MappingRuleDTO();
        rule.setId("rule-" + targetField);
        rule.setLogic(toJsonLogic(matcher));
        rule.setThen(literalResult(outputValue));
        rule.setMetadata(new MappingMetadataDTO());

        MappingInputDTO input = new MappingInputDTO();
        input.setSourceId(sourceId);
        input.setColumn(sourceColumn);

        MappingDefinitionDTO def = new MappingDefinitionDTO();
        def.setId("map-" + targetField);
        def.setTargetField(targetField);
        def.setMappingType("standard");
        def.setInputs(List.of(input));
        def.setRules(List.of(rule));
        def.setMetadata(new MappingMetadataDTO());

        MappingSpecDTO spec = new MappingSpecDTO();
        spec.setSpecVersion("1.0.0");
        spec.setMappings(List.of(def));
        return spec;
    }

    private MappingDefinitionDTO oneHotRangeSpec(String targetField,
                                                 String sourceId,
                                                 String sourceColumn,
                                                 String valueType,
                                                 Number min,
                                                 Number max) {
        MappingMatcherDTO matcher = rangeMatcher(sourceId, sourceColumn, valueType, min, max);

        OneHotOutputDTO output = new OneHotOutputDTO();
        output.setTargetField(targetField);
        output.setLogic(toJsonLogic(matcher));
        output.setTrueValue("1");
        output.setFalseValue("0");
        output.setMetadata(new MappingMetadataDTO());

        MappingInputDTO input = new MappingInputDTO();
        input.setSourceId(sourceId);
        input.setColumn(sourceColumn);

        MappingDefinitionDTO def = new MappingDefinitionDTO();
        def.setId("map-" + targetField);
        def.setGroupName(targetField);
        def.setMappingType("one-hot");
        def.setInputs(List.of(input));
        def.setOutputs(List.of(output));
        def.setMetadata(new MappingMetadataDTO());
        return def;
    }

    private MappingDefinitionDTO customTypedPassthroughSpec(String targetField,
                                                            String sourceId,
                                                            String sourceColumn,
                                                            String valueType) {
        MappingMatcherDTO matcher = new MappingMatcherDTO();
        matcher.setSourceId(sourceId);
        matcher.setColumn(sourceColumn);
        matcher.setMatchType("type");
        matcher.setValueType(valueType);

        MappingRuleDTO rule = new MappingRuleDTO();
        rule.setId("rule-" + targetField);
        rule.setLogic(toJsonLogic(matcher));
        RuleResultDTO result = new RuleResultDTO();
        result.setKind("source-value");
        result.setSourceId(sourceId);
        result.setColumn(sourceColumn);
        rule.setThen(result);
        rule.setMetadata(new MappingMetadataDTO());

        MappingInputDTO input = new MappingInputDTO();
        input.setSourceId(sourceId);
        input.setColumn(sourceColumn);

        MappingDefinitionDTO def = new MappingDefinitionDTO();
        def.setId("map-" + targetField);
        def.setTargetField(targetField);
        def.setMappingType("standard");
        def.setSourceConfigFile("custom_mapping");
        def.setInputs(List.of(input));
        def.setRules(List.of(rule));
        def.setMetadata(new MappingMetadataDTO());
        return def;
    }

    private MappingDefinitionDTO customExactMappingSpec(String targetField,
                                                        String sourceId,
                                                        String sourceColumn,
                                                        String matchValue,
                                                        String outputValue) {
        MappingMatcherDTO matcher = exactMatcher(sourceId, sourceColumn, matchValue);

        MappingRuleDTO rule = new MappingRuleDTO();
        rule.setId("rule-" + targetField);
        rule.setLogic(toJsonLogic(matcher));
        rule.setThen(literalResult(outputValue));
        rule.setMetadata(new MappingMetadataDTO());

        MappingInputDTO input = new MappingInputDTO();
        input.setSourceId(sourceId);
        input.setColumn(sourceColumn);

        MappingDefinitionDTO def = new MappingDefinitionDTO();
        def.setId("map-" + targetField);
        def.setTargetField(targetField);
        def.setMappingType("standard");
        def.setSourceConfigFile("custom_mapping");
        def.setInputs(List.of(input));
        def.setRules(List.of(rule));
        def.setMetadata(new MappingMetadataDTO());
        return def;
    }

    private MappingMatcherDTO exactMatcher(String sourceId, String column, String value) {
        MappingMatcherDTO matcher = new MappingMatcherDTO();
        matcher.setSourceId(sourceId);
        matcher.setColumn(column);
        matcher.setMatchType("exact");
        matcher.setValue(value);
        return matcher;
    }

    private MappingMatcherDTO rangeMatcher(String sourceId, String column, String valueType, Number min, Number max) {
        MappingMatcherDTO matcher = new MappingMatcherDTO();
        matcher.setSourceId(sourceId);
        matcher.setColumn(column);
        matcher.setMatchType("range");
        matcher.setValueType(valueType);
        matcher.setMinValue(min);
        matcher.setMaxValue(max);
        return matcher;
    }

    private RuleResultDTO literalResult(String value) {
        RuleResultDTO result = new RuleResultDTO();
        result.setKind("literal");
        result.setValue(value);
        return result;
    }

    private Map<String, Object> toJsonLogic(MappingMatcherDTO matcher) {
        String sourceId = matcher.getSourceId();
        String column = matcher.getColumn();
        String varPath = sourceId + "::" + column;
        String matchType = matcher.getMatchType() == null
                ? ""
                : matcher.getMatchType().trim().toLowerCase(Locale.ROOT);

        return switch (matchType) {
            case "exact" -> Map.of(
                    "==", List.of(
                            Map.of("var", varPath),
                            matcher.getValue()
                    )
            );
            case "type" -> Map.of(
                    "type", List.of(
                            Map.of("var", varPath),
                            matcher.getValueType()
                    )
            );
            case "range" -> Map.of(
                    "and", List.of(
                            Map.of(">=", List.of(Map.of("var", varPath), matcher.getMinValue())),
                            Map.of("<=", List.of(Map.of("var", varPath), matcher.getMaxValue()))
                    )
            );
            default -> throw new IllegalArgumentException("Unsupported matcher type in test: " + matcher.getMatchType());
        };
    }

    // -----------------------------------------------------------------------
    // startParseJob – covers parseFilesWithProgress async path
    // -----------------------------------------------------------------------

    @Test
    void startParseJob_emptySpec_completesJobSuccessfully() throws Exception {
        makeCsv("data.csv", "col1;col2\n1;2\n3;4\n");

        MappingSpecDTO spec = new MappingSpecDTO();
        spec.setSpecVersion("1.0.0");
        spec.setMappings(List.of());

        String jobId = jobs.createJob();
        harmonizerService.startParseJob(jobId, spec, Map.of("cfg", List.of("data.csv")), null);

        long deadline = System.currentTimeMillis() + 5_000;
        while (jobs.getJob(jobId).getState() == HarmonizationStatusDTO.State.RUNNING
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        HarmonizationProcessingJobs.JobState state = jobs.getJob(jobId);
        assertThat(state.getState()).isEqualTo(HarmonizationStatusDTO.State.DONE);
        assertThat(state.getResult()).isEqualTo("Files processed successfully.");
    }

    @Test
    void startParseJob_withStandardMapping_completesJobSuccessfully() throws Exception {
        makeCsv("patient.csv", "id;name;age\n1;Alice;30\n2;Bob;25\n");

        MappingSpecDTO spec = new MappingSpecDTO();
        spec.setSpecVersion("1.0.0");
        MappingDefinitionDTO mapping = new MappingDefinitionDTO();
        mapping.setTargetField("patient_id");
        mapping.setMappingType("standard");
        MappingInputDTO input = new MappingInputDTO();
        input.setSourceId("cfg");
        input.setColumn("id");
        mapping.setInputs(List.of(input));

        MappingRuleDTO rule = new MappingRuleDTO();
        rule.setLogic(null);
        RuleResultDTO result = new RuleResultDTO();
        result.setKind("column");
        result.setSourceId("cfg");
        result.setColumn("id");
        rule.setThen(result);
        mapping.setRules(List.of(rule));
        mapping.setMetadata(new MappingMetadataDTO());

        spec.setMappings(List.of(mapping));

        String jobId = jobs.createJob();
        harmonizerService.startParseJob(jobId, spec, Map.of("cfg", List.of("patient.csv")), null);

        long deadline = System.currentTimeMillis() + 5_000;
        while (jobs.getJob(jobId).getState() == HarmonizationStatusDTO.State.RUNNING
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertThat(jobs.getJob(jobId).getState()).isEqualTo(HarmonizationStatusDTO.State.DONE);
    }

    @Test
    void startParseJob_withEmptyFileMappings_completesSuccessfully() throws Exception {
        MappingSpecDTO spec = new MappingSpecDTO();
        spec.setSpecVersion("1.0.0");
        spec.setMappings(List.of());

        String jobId = jobs.createJob();
        harmonizerService.startParseJob(jobId, spec, Map.of(), null);

        long deadline = System.currentTimeMillis() + 5_000;
        while (jobs.getJob(jobId).getState() == HarmonizationStatusDTO.State.RUNNING
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertThat(jobs.getJob(jobId).getState()).isIn(
                HarmonizationStatusDTO.State.DONE,
                HarmonizationStatusDTO.State.ERROR);
    }
}
