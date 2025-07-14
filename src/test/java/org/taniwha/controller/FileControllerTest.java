package org.taniwha.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.service.FileService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileControllerTest {

    private MockMvc mvc;
    private FileService fileService;

    @BeforeEach
    void setUp() {
        fileService = mock(FileService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new FileController(fileService))
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
        when(fileService.getElementFilePath("my.txt"))
                .thenReturn(tmp.toString());

        mvc.perform(get("/api/files/dataset_elements/my.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string("hello world"));
        Files.deleteIfExists(tmp);
    }

    @Test
    void getElementFile_serviceThrows_returns500() throws Exception {
        when(fileService.getElementFilePath("none.txt"))
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
        when(fileService.getElementFilePath("bad.txt"))
                .thenReturn(tmp.toString());

        mvc.perform(get("/api/files/dataset_elements/bad.txt"))
                .andExpect(status().isInternalServerError())
                .andExpect(content()
                        .string("Error fetching element file: bad.txt"));
    }
}
