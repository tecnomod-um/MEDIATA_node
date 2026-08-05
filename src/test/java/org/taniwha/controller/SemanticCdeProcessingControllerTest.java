package org.taniwha.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.dto.SemanticCdeConversionDTO;
import org.taniwha.dto.SemanticCdeDatasetMatchDTO;
import org.taniwha.dto.SemanticCdeProcessResponseDTO;
import org.taniwha.service.semanticcde.SemanticCdeProcessingService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SemanticCdeProcessingControllerTest {

    private MockMvc mvc;
    private SemanticCdeProcessingService processingService;

    @BeforeEach
    void setUp() {
        processingService = mock(SemanticCdeProcessingService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new SemanticCdeProcessingController(processingService))
                .build();
    }

    @Test
    void findRegistryMatchesReturnsCompatibleEntries() throws Exception {
        when(processingService.findRegistryMatches(eq("clinical_table.csv")))
                .thenReturn(List.of(new SemanticCdeDatasetMatchDTO(
                        "clinical_table.csv",
                        "Blood pressure",
                        "scde-index/blood_pressure.scde.ttl",
                        List.of(new SemanticCdeConversionDTO(
                                "MAP-BLOOD-PRESSURE-CSV-SULO-001",
                                "CSV to SULO",
                                "Convert CSV blood pressure observations to SULO",
                                "CSV",
                                "SULO",
                                "sulo",
                                List.of("MAP-BLOOD-PRESSURE-CSV-SULO-001")
                        )),
                        List.of("systolic pressure", "diastolic pressure"),
                        List.of("Clinical data element: blood pressure"),
                        0.75,
                        true
                )));

        mvc.perform(get("/api/semantic-cde/datasets/clinical_table.csv/registry-matches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Blood pressure"))
                .andExpect(jsonPath("$[0].conversions[0].target").value("SULO"))
                .andExpect(jsonPath("$[0].datasetFields[0]").value("systolic pressure"))
                .andExpect(jsonPath("$[0].lexicalScore").value(0.75))
                .andExpect(jsonPath("$[0].lexicalMatch").value(true))
                .andExpect(jsonPath("$[0].conversions[0].mappingIds[0]").value("MAP-BLOOD-PRESSURE-CSV-SULO-001"));
    }

    @Test
    void processReturnsOutputsWhenProcessorSucceeds() throws Exception {
        when(processingService.process(any())).thenReturn(new SemanticCdeProcessResponseDTO(
                true,
                "blood_pressure.csv",
                "scde-index/blood_pressure.scde.ttl",
                List.of("MAP-BLOOD-PRESSURE-CSV-SULO-001"),
                List.of("blood_pressure-sulo.ttl"),
                "Semantic-CDE processing completed.",
                "ok"
        ));

        mvc.perform(post("/api/semantic-cde/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "datasetFileName": "blood_pressure.csv",
                                  "semanticCdePath": "scde-index/blood_pressure.scde.ttl",
                                  "mappingIds": ["MAP-BLOOD-PRESSURE-CSV-SULO-001"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.outputFiles[0]").value("blood_pressure-sulo.ttl"));
    }

    @Test
    void processReturnsBadGatewayWhenProcessorFails() throws Exception {
        when(processingService.process(any())).thenReturn(new SemanticCdeProcessResponseDTO(
                false,
                "blood_pressure.csv",
                "scde-index/blood_pressure.scde.ttl",
                List.of("MAP-BLOOD-PRESSURE-CSV-SULO-001"),
                List.of(),
                "Semantic-CDE processing failed.",
                "Mapping execution failed"
        ));

        mvc.perform(post("/api/semantic-cde/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "datasetFileName": "blood_pressure.csv",
                                  "semanticCdePath": "scde-index/blood_pressure.scde.ttl",
                                  "mappingIds": ["MAP-BLOOD-PRESSURE-CSV-SULO-001"]
                                }
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false));
    }
}
