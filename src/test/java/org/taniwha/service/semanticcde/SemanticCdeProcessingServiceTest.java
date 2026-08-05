package org.taniwha.service.semanticcde;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.taniwha.dto.SemanticCdeProcessRequestDTO;
import org.taniwha.security.FileFilter;
import org.taniwha.service.FileService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SemanticCdeProcessingServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void findRegistryMatchesUsesLocalDatasetShapeWithoutExposingRows() throws Exception {
        Path appPath = tempDir.resolve("node-data");
        Path datasets = appPath.resolve("datasets");
        Files.createDirectories(datasets);
        Files.writeString(datasets.resolve("clinical_table.data"),
                "observation_id,patient_id,performer_id,device_id,observation_time,\"systolic_value\","
                        + "diastolic_value,unit_source_value,body_site_label,body_site_system,body_site_code,body_site_display\n"
                        + "o1,p1,pr1,d1,2026-05-21T09:15:00Z,120,80,mm[Hg],Left arm,http://snomed.info/sct,368208006,Left arm\n");

        SemanticCdeProcessingService service = new SemanticCdeProcessingService(
                new FileService(mock(FileFilter.class), appPath.toString(), ""),
                new SemanticCdeProcessorProperties("java", "/tmp/semanticor.jar",
                        null,
                        "org.semanticor.SemanticorApplication",
                        "https://semantics.inf.um.es/mediata/scde",
                        30),
                command -> new SemanticCdeProcessorResult(0, "")
        );

        var matches = service.findRegistryMatches("clinical_table.data");

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).title()).containsIgnoringCase("blood pressure");
        assertThat(matches.get(0).conversions()).extracting("source").contains("CSV");
        assertThat(matches.get(0).conversions()).extracting("target")
                .contains("SULO", "FHIR", "OMOP", "openEHR");
        assertThat(matches.get(0).conversions())
                .filteredOn(conversion -> "FHIR".equals(conversion.target()))
                .singleElement()
                .satisfies(conversion -> assertThat(conversion.mappingIds()).containsExactly(
                        "DERIVE-BLOOD-PRESSURE-CSV-SULO-001",
                        "DERIVE-BLOOD-PRESSURE-SULO-FHIR-001"
                ));
        assertThat(matches.get(0).datasetFileName()).isEqualTo("clinical_table.data");
        assertThat(matches.get(0).datasetFields())
                .contains("systolic value", "diastolic value")
                .doesNotContain("patient id", "body site label", "unit source value");
        assertThat(matches.get(0).semanticDescriptions())
                .anyMatch(description -> description.contains("blood pressure"));
        assertThat(matches.get(0).lexicalScore()).isGreaterThan(0.0);
    }

    @Test
    void findRegistryMatchesUsesPublishedMappingFieldsAsExecutablePreflight() throws Exception {
        Path appPath = tempDir.resolve("node-data");
        Path datasets = appPath.resolve("datasets");
        Files.createDirectories(datasets);
        Files.writeString(datasets.resolve("reordered.csv"),
                "unit_source_value,diastolic_value,systolic_value,observation_time,device_id,performer_id,patient_id,observation_id,"
                        + "body_site_display,body_site_code,body_site_system,body_site_label\n"
                        + "mm[Hg],80,120,2026-05-21T09:15:00Z,d1,pr1,p1,o1,Left arm,368208006,http://snomed.info/sct,Left arm\n");
        Files.writeString(datasets.resolve("missing-column.csv"),
                "observation_id,patient_id,performer_id,device_id,observation_time,systolic_value,unit_source_value,"
                        + "body_site_label,body_site_system,body_site_code,body_site_display\n"
                        + "o1,p1,pr1,d1,2026-05-21T09:15:00Z,120,mm[Hg],Left arm,http://snomed.info/sct,368208006,Left arm\n");

        SemanticCdeProcessingService service = new SemanticCdeProcessingService(
                new FileService(mock(FileFilter.class), appPath.toString(), ""),
                new SemanticCdeProcessorProperties("java", "/tmp/semanticor.jar",
                        null,
                        "org.semanticor.SemanticorApplication",
                        "https://semantics.inf.um.es/mediata/scde",
                        30),
                command -> new SemanticCdeProcessorResult(0, "")
        );

        assertThat(service.findRegistryMatches("reordered.csv")).hasSize(1);
        assertThat(service.findRegistryMatches("missing-column.csv")).isEmpty();
    }

    @Test
    void findRegistryMatchesRejectsUnitsOutsideTheRegistryValueDomain() throws Exception {
        Path appPath = tempDir.resolve("node-data");
        Path datasets = appPath.resolve("datasets");
        Files.createDirectories(datasets);
        Files.writeString(datasets.resolve("blood_pressure_kpa.csv"),
                "patient_id,systolic_value,diastolic_value,unit_source_value\np1,16.0,10.7,kPa\n");

        SemanticCdeProcessingService service = new SemanticCdeProcessingService(
                new FileService(mock(FileFilter.class), appPath.toString(), ""),
                new SemanticCdeProcessorProperties("java", "/tmp/semanticor.jar",
                        null,
                        "org.semanticor.SemanticorApplication",
                        "https://semantics.inf.um.es/mediata/scde",
                        30),
                command -> new SemanticCdeProcessorResult(0, "")
        );

        assertThat(service.findRegistryMatches("blood_pressure_kpa.csv")).isEmpty();
    }

    @Test
    void processInvokesProcessorForLocalDatasetAndHostedSemanticCdeResource() throws Exception {
        Path appPath = tempDir.resolve("node-data");
        Path datasets = appPath.resolve("datasets");
        Files.createDirectories(datasets);
        Files.writeString(datasets.resolve("blood_pressure.csv"), "id,value\n1,120\n");

        FileService fileService = new FileService(mock(FileFilter.class), appPath.toString(), "");
        SemanticCdeProcessorProperties properties = new SemanticCdeProcessorProperties(
                "java",
                "/tmp/semanticor.jar",
                null,
                "org.semanticor.SemanticorApplication",
                "https://semantics.inf.um.es/mediata/scde",
                30
        );
        CapturingProcessorClient processorClient = new CapturingProcessorClient("blood_pressure-sulo.ttl");
        SemanticCdeProcessingService service = new SemanticCdeProcessingService(fileService, properties, processorClient);

        var response = service.process(new SemanticCdeProcessRequestDTO(
                "blood_pressure.csv",
                "scde-index/blood_pressure.scde.ttl",
                List.of("MAP-BLOOD-PRESSURE-CSV-SULO-001"),
                null
        ));

        assertThat(response.success()).isTrue();
        assertThat(response.outputFiles()).containsExactly("blood_pressure-sulo.ttl");
        assertThat(processorClient.command.inputFile()).isEqualTo(datasets.resolve("blood_pressure.csv"));
        assertThat(processorClient.command.semanticCdeResource())
                .isEqualTo("https://semantics.inf.um.es/mediata/scde/scde-index/blood_pressure.scde.ttl");
        assertThat(processorClient.command.mappingIds()).containsExactly("MAP-BLOOD-PRESSURE-CSV-SULO-001");
        assertThat(processorClient.command.outputPath()).isEqualTo(appPath.resolve("mapped_datasets"));
        assertThat(Files.exists(datasets.resolve("blood_pressure-sulo.ttl"))).isFalse();
    }

    @Test
    void processReportsAnExistingOutputThatWasOverwritten() throws Exception {
        Path appPath = tempDir.resolve("node-data");
        Path datasets = appPath.resolve("datasets");
        Path outputs = appPath.resolve("mapped_datasets");
        Files.createDirectories(datasets);
        Files.createDirectories(outputs);
        Files.writeString(datasets.resolve("blood_pressure.csv"), "id,value\n1,120\n");
        Files.writeString(outputs.resolve("blood_pressure-sulo.ttl"), "old");

        SemanticCdeProcessingService service = new SemanticCdeProcessingService(
                new FileService(mock(FileFilter.class), appPath.toString(), ""),
                new SemanticCdeProcessorProperties("java", "/tmp/semanticor.jar",
                        null,
                        "org.semanticor.SemanticorApplication",
                        "https://semantics.inf.um.es/mediata/scde",
                        30),
                new CapturingProcessorClient("blood_pressure-sulo.ttl")
        );

        var response = service.process(new SemanticCdeProcessRequestDTO(
                "blood_pressure.csv",
                "scde-index/blood_pressure.scde.ttl",
                List.of("MAP-BLOOD-PRESSURE-CSV-SULO-001"),
                null
        ));

        assertThat(response.outputFiles()).containsExactly("blood_pressure-sulo.ttl");
    }

    @Test
    void processRejectsSemanticCdePathTraversal() throws Exception {
        Path appPath = tempDir.resolve("node-data");
        Files.createDirectories(appPath.resolve("datasets"));
        Files.writeString(appPath.resolve("datasets/data.csv"), "x\n");
        SemanticCdeProcessingService service = new SemanticCdeProcessingService(
                new FileService(mock(FileFilter.class), appPath.toString(), ""),
                new SemanticCdeProcessorProperties("java", "/tmp/semanticor.jar",
                        null,
                        "org.semanticor.SemanticorApplication",
                        "https://semantics.inf.um.es/mediata/scde",
                        30),
                command -> new SemanticCdeProcessorResult(0, "")
        );

        assertThatThrownBy(() -> service.process(new SemanticCdeProcessRequestDTO(
                "data.csv",
                "../secret.ttl",
                List.of("MAP-1"),
                null
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Semantic-CDE path");
    }

    @Test
    void processUsesHostedSemanticCdeRepositoryResource() throws Exception {
        Path appPath = tempDir.resolve("node-data");
        Path datasets = appPath.resolve("datasets");
        Files.createDirectories(datasets);
        Files.writeString(datasets.resolve("blood_pressure.csv"), "id,value\n1,120\n");

        String repositoryUrl = "https://registry.example/semantic-cde";
        FileService fileService = new FileService(mock(FileFilter.class), appPath.toString(), "");
        SemanticCdeProcessorProperties properties = new SemanticCdeProcessorProperties(
                "java",
                "/tmp/semanticor.jar",
                null,
                "org.semanticor.SemanticorApplication",
                repositoryUrl,
                30
        );
        CapturingProcessorClient processorClient = new CapturingProcessorClient("blood_pressure-sulo.ttl");
        SemanticCdeProcessingService service = new SemanticCdeProcessingService(fileService, properties, processorClient);

        var response = service.process(new SemanticCdeProcessRequestDTO(
                "blood_pressure.csv",
                "scde-index/blood_pressure.scde.ttl",
                List.of("MAP-BLOOD-PRESSURE-CSV-SULO-001"),
                null
        ));

        assertThat(response.success()).isTrue();
        assertThat(processorClient.command.semanticCdeResource())
                .isEqualTo("https://registry.example/semantic-cde/scde-index/blood_pressure.scde.ttl");
    }

    @Test
    void processAcceptsLegacyCompositePipelineMappingId() throws Exception {
        Path appPath = tempDir.resolve("node-data");
        Path datasets = appPath.resolve("datasets");
        Files.createDirectories(datasets);
        Files.writeString(datasets.resolve("blood_pressure.csv"), "id,value\n1,120\n");

        FileService fileService = new FileService(mock(FileFilter.class), appPath.toString(), "");
        SemanticCdeProcessorProperties properties = new SemanticCdeProcessorProperties(
                "java",
                "/tmp/semanticor.jar",
                null,
                "org.semanticor.SemanticorApplication",
                "https://semantics.inf.um.es/mediata/scde",
                30
        );
        CapturingProcessorClient processorClient = new CapturingProcessorClient("blood_pressure-FHIR.json");
        SemanticCdeProcessingService service = new SemanticCdeProcessingService(fileService, properties, processorClient);

        service.process(new SemanticCdeProcessRequestDTO(
                "blood_pressure.csv",
                "scde-index/blood_pressure.scde.ttl",
                List.of("DERIVE-BLOOD-PRESSURE-CSV-SULO-001__DERIVE-BLOOD-PRESSURE-SULO-FHIR-001"),
                null
        ));

        assertThat(processorClient.command.mappingIds()).containsExactly(
                "DERIVE-BLOOD-PRESSURE-CSV-SULO-001",
                "DERIVE-BLOOD-PRESSURE-SULO-FHIR-001"
        );
    }

    private static class CapturingProcessorClient implements SemanticCdeProcessorClient {
        private final String outputFileName;
        private SemanticCdeProcessorCommand command;

        private CapturingProcessorClient(String outputFileName) {
            this.outputFileName = outputFileName;
        }

        @Override
        public SemanticCdeProcessorResult execute(SemanticCdeProcessorCommand command) throws java.io.IOException {
            this.command = command;
            Path outputFile = Files.isDirectory(command.outputPath()) || !command.outputPath().getFileName().toString().contains(".")
                    ? command.outputPath().resolve(outputFileName)
                    : command.outputPath();
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, "@prefix sulo: <https://w3id.org/sulo/> .");
            return new SemanticCdeProcessorResult(0, "ok");
        }
    }
}
