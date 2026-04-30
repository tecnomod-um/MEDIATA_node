package org.taniwha.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.model.FairDataPointSyncResult;
import org.taniwha.service.FairDataPointCatalogSyncService;
import org.taniwha.service.FairDataPointInstanceService;
import org.taniwha.util.JwtTokenUtil;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FairDataPointControllerTest {

    private MockMvc mvc;
    private FairDataPointCatalogSyncService fairDataPointCatalogSyncService;
    private FairDataPointInstanceService fairDataPointInstanceService;
    private JwtTokenUtil jwtTokenUtil;

    @BeforeEach
    void setUp() {
        fairDataPointCatalogSyncService = mock(FairDataPointCatalogSyncService.class);
        fairDataPointInstanceService = mock(FairDataPointInstanceService.class);
        jwtTokenUtil = mock(JwtTokenUtil.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new FairDataPointController(
                        fairDataPointCatalogSyncService,
                        fairDataPointInstanceService,
                        jwtTokenUtil,
                        ""
                ))
                .build();
    }

    @Test
    void syncCatalogs_success_returnsPublishedSummary() throws Exception {
        when(jwtTokenUtil.isNodeAccessToken("NODE.JWT")).thenReturn(true);
        when(fairDataPointCatalogSyncService.publishCatalogs()).thenReturn(
                new FairDataPointSyncResult(
                        "COMPLETED",
                        2,
                        1,
                        2,
                        2,
                        List.of("http://fdp:8080/catalog/123"),
                        List.of("http://fdp:8080/dataset/abc", "http://fdp:8080/dataset/def"),
                        List.of("http://fdp:8080/distribution/ghi", "http://fdp:8080/distribution/jkl"),
                        "Catalog metadata was published to the FAIR Data Point instance."
                )
        );

        mvc.perform(post("/api/fairdatapoint/sync")
                        .header("Authorization", "Bearer NODE.JWT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.metadataFilesRead").value(2))
                .andExpect(jsonPath("$.catalogsPublished").value(1))
                .andExpect(jsonPath("$.datasetsPublished").value(2))
                .andExpect(jsonPath("$.distributionsPublished").value(2))
                .andExpect(jsonPath("$.publishedCatalogUris[0]").value("http://fdp:8080/catalog/123"));
    }

    @Test
    void syncCatalogs_disabledIntegration_returnsBadRequest() throws Exception {
        when(jwtTokenUtil.isNodeAccessToken("NODE.JWT")).thenReturn(true);
        when(fairDataPointCatalogSyncService.publishCatalogs())
                .thenThrow(new IllegalStateException("FAIR Data Point integration is disabled"));

        mvc.perform(post("/api/fairdatapoint/sync")
                        .header("Authorization", "Bearer NODE.JWT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.message").value("FAIR Data Point integration is disabled"));
    }

    @Test
    void syncCatalogs_nonNodeValidatedToken_returnsForbidden() throws Exception {
        when(jwtTokenUtil.isNodeAccessToken("GENERIC.JWT")).thenReturn(false);

        mvc.perform(post("/api/fairdatapoint/sync")
                        .header("Authorization", "Bearer GENERIC.JWT"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.message").value("FAIR Data Point sync requires a node-validated token issued by /node/validate."));
    }

    @Test
    void syncCatalogs_missingAuthorizationHeader_returnsForbidden() throws Exception {
        mvc.perform(post("/api/fairdatapoint/sync"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("ERROR"));
    }
}
