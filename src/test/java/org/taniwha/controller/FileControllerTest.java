package org.taniwha.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.model.NodeMetadata;
import org.taniwha.service.FileService;
import org.taniwha.util.JwtTokenUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileControllerTest {

    private MockMvc mvc;
    private FileService fileService;
    private JwtTokenUtil jwtTokenUtil;

    @BeforeEach
    void setUp() {
        fileService = mock(FileService.class);
        jwtTokenUtil = mock(JwtTokenUtil.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new FileController(fileService, jwtTokenUtil))
                .build();
    }

    @Test
    void listDatasetFiles_success() throws Exception {
        when(fileService.listDatasetFiles())
                .thenReturn(List.of("a.csv", "b.csv"));

        mvc.perform(get("/api/files/datasets"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0]").value("a.csv"))
                .andExpect(jsonPath("$[1]").value("b.csv"));
    }

    @Test
    void listDatasetFiles_serviceThrows_returns500() throws Exception {
        when(fileService.listDatasetFiles())
                .thenThrow(new RuntimeException("boom"));

        mvc.perform(get("/api/files/datasets"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$[0]").value("Error listing files"));
    }

    @Test
    void listMappedDatasetFiles_success() throws Exception {
        when(fileService.listMappedDatasetFiles())
                .thenReturn(List.of("m1.csv"));

        mvc.perform(get("/api/files/mapped_datasets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("m1.csv"));
    }

    @Test
    void listMappedDatasetFiles_serviceThrows_returns500() throws Exception {
        when(fileService.listMappedDatasetFiles())
                .thenThrow(new RuntimeException("err"));

        mvc.perform(get("/api/files/mapped_datasets"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$[0]").value("Error listing files"));
    }

    @Test
    void listFhirMappingFiles_success() throws Exception {
        when(fileService.listFhirMappingFiles())
                .thenReturn(List.of("f1.json"));

        mvc.perform(get("/api/files/fhir_mappings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("f1.json"));
    }

    @Test
    void listFhirMappingFiles_serviceThrows_returns500() throws Exception {
        when(fileService.listFhirMappingFiles())
                .thenThrow(new RuntimeException("oops"));

        mvc.perform(get("/api/files/fhir_mappings"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$[0]").value("Error listing files"));
    }

    @Test
    void listDatasetElements_success() throws Exception {
        when(fileService.listElementFiles())
                .thenReturn(List.of("e1.csv"));

        mvc.perform(get("/api/files/dataset_elements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("e1.csv"));
    }

    @Test
    void listDatasetElements_serviceThrows_returns500() throws Exception {
        when(fileService.listElementFiles())
                .thenThrow(new RuntimeException("bad"));

        mvc.perform(get("/api/files/dataset_elements"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$[0]").value("Error listing files"));
    }

    @Test
    void saveDatasetElements_success() throws Exception {
        String fname = "out.csv";
        String csv = "a,b,c\n1,2,3";
        when(fileService.saveDatasetElements(eq(fname), eq(csv)))
                .thenReturn("/tmp/out.csv");

        mvc.perform(post("/api/files/save_dataset_elements")
                        .param("fileName", fname)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(csv))
                .andExpect(status().isOk())
                .andExpect(content().string("Dataset elements saved successfully."));
    }

    @Test
    void saveDatasetElements_serviceThrows_returns500() throws Exception {
        when(fileService.saveDatasetElements(anyString(), anyString()))
                .thenThrow(new RuntimeException("disk full"));

        mvc.perform(post("/api/files/save_dataset_elements")
                        .param("fileName", "f.csv")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("data"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("Error saving dataset elements."));
    }

    @Test
    void getElementFile_success() throws Exception {
        Path tmp = Files.createTempFile("elem", ".txt");
        Files.writeString(tmp, "hello world");
        when(fileService.resolveElementFilePath("my.txt"))
                .thenReturn(tmp);

        mvc.perform(get("/api/files/dataset_elements/my.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string("hello world"));
        Files.deleteIfExists(tmp);
    }

    @Test
    void getElementFile_serviceThrows_returns500() throws Exception {
        when(fileService.resolveElementFilePath("none.txt"))
                .thenThrow(new RuntimeException("not found"));

        mvc.perform(get("/api/files/dataset_elements/none.txt"))
                .andExpect(status().isInternalServerError())
                .andExpect(content()
                        .string("Error fetching element file: none.txt"));
    }

    @Test
    void getElementFile_badPath_throwsReading_returns500() throws Exception {
        Path tmp = Files.createTempFile("bad", ".txt");
        Files.deleteIfExists(tmp);
        when(fileService.resolveElementFilePath("bad.txt"))
                .thenReturn(tmp);

        mvc.perform(get("/api/files/dataset_elements/bad.txt"))
                .andExpect(status().isInternalServerError())
                .andExpect(content()
                        .string("Error fetching element file: bad.txt"));
    }

    @Test
    void getDatasetFile_success() throws Exception {
        Path tmp = Files.createTempFile("dataset", ".csv");
        Files.writeString(tmp, "col1,col2\n1,2\n");
        when(fileService.isDatasetDownloadAllowed("my.csv")).thenReturn(true);
        when(fileService.resolveSharedDatasetFilePath("my.csv"))
                .thenReturn(tmp);

        mvc.perform(get("/api/files/datasets/my.csv"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("my.csv")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string("col1,col2\n1,2\n"));

        Files.deleteIfExists(tmp);
    }

    @Test
    void getDatasetFile_serviceThrows_returns500() throws Exception {
        when(fileService.isDatasetDownloadAllowed("missing.csv")).thenReturn(true);
        when(fileService.resolveSharedDatasetFilePath("missing.csv"))
                .thenThrow(new RuntimeException("missing"));

        mvc.perform(get("/api/files/datasets/missing.csv"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("Error fetching dataset file: missing.csv"));
    }

    @Test
    void getDatasetFile_blockedByPolicy_returns403() throws Exception {
        when(fileService.isDatasetDownloadAllowed("my.csv")).thenReturn(false);
        when(fileService.datasetDownloadBlockedMessage("my.csv"))
                .thenReturn("This dataset has been configured to not leave the server. Download is disabled for my.csv.");

        mvc.perform(get("/api/files/datasets/my.csv"))
                .andExpect(status().isForbidden())
                .andExpect(content().string("This dataset has been configured to not leave the server. Download is disabled for my.csv."));
    }

    @Test
    void updateDatasetShareability_requiresNodeValidatedToken() throws Exception {
        mvc.perform(post("/api/files/datasets/shareability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"samplefile.csv","downloadable":true}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Updating dataset shareability requires a node-validated token issued by /node/validate."));
    }

    @Test
    void updateDatasetShareability_updatesDatasetFamily() throws Exception {
        when(jwtTokenUtil.isNodeAccessToken("node-token")).thenReturn(true);
        when(fileService.setDatasetFamilyDownloadable("samplefile.csv", true))
                .thenReturn(List.of("samplefile.csv", "parsed_samplefile.csv"));
        when(fileService.logicalDatasetIdFor("samplefile.csv")).thenReturn("samplefile");

        mvc.perform(post("/api/files/datasets/shareability")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer node-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"samplefile.csv","downloadable":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logicalDatasetId").value("samplefile"))
                .andExpect(jsonPath("$.downloadable").value(true))
                .andExpect(jsonPath("$.affectedFiles[0]").value("samplefile.csv"))
                .andExpect(jsonPath("$.affectedFiles[1]").value("parsed_samplefile.csv"));
    }

    @Test
    void getRawDcatMetadata_success() throws Exception {
        when(fileService.getRawNodeMetadata()).thenReturn("@prefix dcat: <http://www.w3.org/ns/dcat#> .");
        when(fileService.getRawNodeMetadataFileName()).thenReturn("catalog.ttl");

        mvc.perform(get("/api/files/metadata/dcat"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("catalog.ttl")))
                .andExpect(content().string("@prefix dcat: <http://www.w3.org/ns/dcat#> ."));
    }

    @Test
    void getRawDcatMetadata_missing_returns404() throws Exception {
        when(fileService.getRawNodeMetadata()).thenReturn(null);

        mvc.perform(get("/api/files/metadata/dcat"))
                .andExpect(status().isNotFound());

        verify(fileService, never()).getRawNodeMetadataFileName();
    }

    @Test
    void getFormattedDcatMetadata_success() throws Exception {
        NodeMetadata metadata = new NodeMetadata();
        metadata.setSourceFile("catalog.ttl");
        metadata.setDataset(List.of());
        when(fileService.parseNodeMetadata()).thenReturn(metadata);

        mvc.perform(get("/api/files/metadata/dcat/formatted"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sourceFile").value("catalog.ttl"))
                .andExpect(jsonPath("$.dataset").isArray())
                .andExpect(jsonPath("$.dataset").isEmpty());
    }

    @Test
    void getFormattedDcatMetadata_missing_returns404() throws Exception {
        when(fileService.parseNodeMetadata()).thenReturn(null);

        mvc.perform(get("/api/files/metadata/dcat/formatted"))
                .andExpect(status().isNotFound());
    }
}
