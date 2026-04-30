package org.taniwha.util;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.taniwha.model.MetadataDocument;
import org.taniwha.security.FileFilter;
import org.taniwha.service.FileService;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

class FairDataPointMetadataUtilTest {

    @Mock
    private FileFilter fileFilter;

    @TempDir
    Path tempBase;

    private FileService fileService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        doNothing().when(fileFilter).validate(any(Path.class));
        fileService = new FileService(fileFilter, tempBase.toString());
    }

    @Test
    void generateManagedMetadataDocument_createsCompliantCatalogDatasetAndDistributionMetadata() throws Exception {
        Files.createDirectories(tempBase.resolve("datasets"));
        Files.writeString(tempBase.resolve("datasets/Barthel.csv"), "score\n100\n");
        Files.writeString(tempBase.resolve("datasets/FIM.csv"), "score\n90\n");

        FairDataPointMetadataUtil util = new FairDataPointMetadataUtil(
                fileService,
                "Node Alpha",
                "Rehabilitation datasets",
                "http://node.example:8080",
                "8080",
                "/taniwha"
        );

        MetadataDocument document = util.generateManagedMetadataDocument();

        assertThat(document.fileName()).isEqualTo("fairdatapoint-generated.ttl");
        assertThat(tempBase.resolve("dataset_metadata").resolve(document.fileName())).exists();
        assertThat(document.content()).contains("Node Alpha FAIR metadata catalog");
        assertThat(document.content()).contains("http://node.example:8080/taniwha/fdp/catalog/node-catalog");
        assertThat(document.content()).contains("/fdp/access/barthel");
        assertThat(document.content()).contains("/fdp/access/fim");
        assertThat(document.content()).contains("\"text/csv\"");

        Model model = DCatUtil.readModel(document.content(), document.fileName());

        assertThat(model.listResourcesWithProperty(RDF.type, model.createResource("http://www.w3.org/ns/dcat#Catalog")).toList())
                .hasSize(1);
        assertThat(model.listResourcesWithProperty(RDF.type, model.createResource("http://www.w3.org/ns/dcat#Dataset")).toList())
                .hasSize(2);
        assertThat(model.listResourcesWithProperty(RDF.type, model.createResource("http://www.w3.org/ns/dcat#Distribution")).toList())
                .hasSize(2);
    }

    @Test
    void generateManagedMetadataDocument_usesLocalFallbackBaseUrlAndMediaTypes() throws Exception {
        Files.createDirectories(tempBase.resolve("datasets"));
        Files.writeString(tempBase.resolve("datasets/outcomes.tsv"), "score\t100\n");
        Files.writeString(tempBase.resolve("datasets/report.xlsx"), "placeholder");

        FairDataPointMetadataUtil util = new FairDataPointMetadataUtil(
                fileService,
                "Node Beta",
                "Spreadsheet datasets",
                "",
                "9090",
                "/"
        );

        MetadataDocument document = util.generateManagedMetadataDocument();

        assertThat(document.content()).contains("http://localhost:9090/fdp/catalog/node-catalog");
        assertThat(document.content()).contains("/fdp/access/outcomes");
        assertThat(document.content()).contains("/fdp/access/report");
        assertThat(document.content()).contains("\"text/tab-separated-values\"");
        assertThat(document.content())
                .contains("\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\"");
    }
}
