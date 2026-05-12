package org.taniwha.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.taniwha.config.FairDataPointBrandingConfig;
import org.taniwha.dto.FairDataPointAccessResponseDTO;
import org.taniwha.service.FairDataPointCatalogSyncService;
import org.taniwha.service.FairDataPointInstanceService;
import org.taniwha.util.JwtTokenUtil;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FairDataPointMetadataControllerTest {

    private MockMvc mvc;
    private FairDataPointInstanceService instanceService;
    private FairDataPointCatalogSyncService syncService;
    private FairDataPointBrandingConfig fairDataPointBrandingConfig;
    private JwtTokenUtil jwtTokenUtil;

    @BeforeEach
    void setUp() {
        instanceService = mock(FairDataPointInstanceService.class);
        syncService = mock(FairDataPointCatalogSyncService.class);
        fairDataPointBrandingConfig = mock(FairDataPointBrandingConfig.class);
        jwtTokenUtil = mock(JwtTokenUtil.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new FairDataPointController(syncService, instanceService, fairDataPointBrandingConfig, jwtTokenUtil, ""))
                .addFilters(new ForwardedHeaderFilter())
                .build();
    }

    @Test
    void rootMetadata_defaultsToTurtle() throws Exception {
        when(instanceService.fetchMetadata(
                anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                anyString()
        )).thenReturn(new FairDataPointInstanceService.FetchResult(
                org.springframework.http.ResponseEntity.ok()
                        .contentType(MediaType.valueOf("text/turtle"))
                        .body("@prefix dct: <http://purl.org/dc/terms/> ."),
                FairDataPointInstanceService.FetchStatus.OK
        ));

        mvc.perform(get("/fdp"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/turtle"))
                .andExpect(content().string("@prefix dct: <http://purl.org/dc/terms/> ."));
    }

    @ParameterizedTest
    @CsvSource({
            "/fdp/,/",
            "/fdp/catalog,/catalog",
            "/fdp/catalog/,/catalog/",
            "/fdp/catalog/demo,/catalog/demo",
            "/fdp/catalog/demo/dataset/,/catalog/demo/dataset/",
            "/fdp/dataset/barthel,/dataset/barthel",
            "/fdp/dataset/barthel/distribution/,/dataset/barthel/distribution/",
            "/fdp/distribution/file-1,/distribution/file-1",
            "/fdp/profile/profile-1,/profile/profile-1",
            "/fdp/metadata-schemas/schema-1,/metadata-schemas/schema-1",
            "/fdp/spec,/spec",
            "/fdp/catalog/demo/spec,/catalog/demo/spec",
            "/fdp/dataset/barthel/spec,/dataset/barthel/spec",
            "/fdp/distribution/file-1/spec,/distribution/file-1/spec",
            "/fdp/shapes/shape-1,/shapes/shape-1"
    })
    void metadataRoutes_delegateToInstanceServiceWithExpectedSubPath(String route, String expectedSubPath) throws Exception {
        when(instanceService.fetchMetadata(anyString(), org.mockito.ArgumentMatchers.nullable(String.class), anyString()))
                .thenReturn(new FairDataPointInstanceService.FetchResult(
                        org.springframework.http.ResponseEntity.ok()
                                .contentType(MediaType.valueOf("text/turtle"))
                                .body("@prefix dct: <http://purl.org/dc/terms/> ."),
                        FairDataPointInstanceService.FetchStatus.OK
                ));

        mvc.perform(get(route))
                .andExpect(status().isOk());

        verify(instanceService).fetchMetadata(
                eq(expectedSubPath),
                org.mockito.ArgumentMatchers.nullable(String.class),
                eq("http://localhost/fdp")
        );
    }

    @Test
    void rootMetadata_prefersInstanceMetadataWhenAvailable() throws Exception {
        when(instanceService.fetchMetadata(
                anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                anyString()
        )).thenReturn(new FairDataPointInstanceService.FetchResult(
                org.springframework.http.ResponseEntity.ok()
                        .contentType(MediaType.valueOf("text/turtle"))
                        .body("@prefix dct: <http://purl.org/dc/terms/> . <http://example.test/taniwha/fdp> dct:title \"Instance\" ."),
                FairDataPointInstanceService.FetchStatus.OK
        ));

        mvc.perform(get("/fdp"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/turtle"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"Instance\"")));
    }

    @Test
    void datasetMetadata_supportsJsonLdNegotiation() throws Exception {
        when(instanceService.fetchMetadata(anyString(), anyString(), anyString()))
                .thenReturn(new FairDataPointInstanceService.FetchResult(
                        org.springframework.http.ResponseEntity.ok()
                                .contentType(MediaType.valueOf("application/ld+json"))
                                .body("{\"@context\":{}}"),
                        FairDataPointInstanceService.FetchStatus.OK
                ));

        mvc.perform(get("/fdp/dataset/barthel")
                        .accept("application/ld+json"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/ld+json"))
                .andExpect(content().string("{\"@context\":{}}"));
    }

    @Test
    void metadataSchema_requestsAreProxiedThroughInstanceSpecFacade() throws Exception {
        when(instanceService.fetchMetadata(anyString(), org.mockito.ArgumentMatchers.nullable(String.class), anyString()))
                .thenReturn(new FairDataPointInstanceService.FetchResult(
                        org.springframework.http.ResponseEntity.ok()
                                .contentType(MediaType.valueOf("text/turtle"))
                                .body("@prefix sh: <http://www.w3.org/ns/shacl#> ."),
                        FairDataPointInstanceService.FetchStatus.OK
                ));

        mvc.perform(get("/fdp/metadata-schemas/a92958ab-a414-47e6-8e17-68ba96ba3a2b"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/turtle"))
                .andExpect(content().string("@prefix sh: <http://www.w3.org/ns/shacl#> ."));
    }

    @Test
    void datasetMetadata_returnsNotFoundWhenInstanceDoesNotHaveRoute() throws Exception {
        when(instanceService.fetchMetadata(
                anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                anyString()
        ))
                .thenReturn(new FairDataPointInstanceService.FetchResult(
                        null,
                        FairDataPointInstanceService.FetchStatus.NOT_FOUND
                ));

        mvc.perform(get("/fdp/dataset/barthel"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rootMetadata_returnsServiceUnavailableWhenInstanceIsDown() throws Exception {
        when(instanceService.fetchMetadata(
                anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                anyString()
        )).thenReturn(new FairDataPointInstanceService.FetchResult(
                null,
                FairDataPointInstanceService.FetchStatus.UNAVAILABLE
        ));

        mvc.perform(get("/fdp"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("FAIR Data Point instance is unavailable."));
    }

    @Test
    void accessInformation_returnsStructuredNodeAuthGuidance() throws Exception {
        when(instanceService.buildAccessResponse(anyString(), org.mockito.ArgumentMatchers.eq("barthel")))
                .thenReturn(new FairDataPointAccessResponseDTO(
                        "barthel",
                        "barthel",
                        "Barthel.csv",
                        "Use node authorization",
                        "http://example.test/taniwha/node/authorize",
                        "http://example.test/taniwha/node/validate",
                        "http://example.test/taniwha/api/files/datasets/Barthel.csv"
                ));

        mvc.perform(get("/fdp/access/barthel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distributionId").value("barthel"))
                .andExpect(jsonPath("$.authorizeEndpoint").value("http://example.test/taniwha/node/authorize"));
    }

    @Test
    void accessInformation_returnsNotFoundWhenDistributionIsUnknown() throws Exception {
        when(instanceService.buildAccessResponse(anyString(), org.mockito.ArgumentMatchers.eq("missing")))
                .thenReturn(null);

        mvc.perform(get("/fdp/access/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownDistribution_returnsNotFound() throws Exception {
        when(instanceService.fetchMetadata(
                anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                anyString()
        ))
                .thenReturn(new FairDataPointInstanceService.FetchResult(
                        null,
                        FairDataPointInstanceService.FetchStatus.NOT_FOUND
                ));

        mvc.perform(get("/fdp/distribution/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void metadataRoutes_resolveBaseUrlWithContextPath() throws Exception {
        when(instanceService.fetchMetadata(anyString(), org.mockito.ArgumentMatchers.nullable(String.class), anyString()))
                .thenReturn(new FairDataPointInstanceService.FetchResult(
                        org.springframework.http.ResponseEntity.ok()
                                .contentType(MediaType.valueOf("text/turtle"))
                                .body("@prefix dct: <http://purl.org/dc/terms/> ."),
                        FairDataPointInstanceService.FetchStatus.OK
                ));

        mvc.perform(get("/taniwha/fdp/dataset/barthel")
                        .contextPath("/taniwha"))
                .andExpect(status().isOk());

        verify(instanceService).fetchMetadata(
                eq("/dataset/barthel"),
                org.mockito.ArgumentMatchers.nullable(String.class),
                eq("http://localhost/taniwha/fdp")
        );
    }

    @Test
    void metadataRoutes_useForwardedHeadersForPublicFdpBaseUrl() throws Exception {
        when(instanceService.fetchMetadata(anyString(), org.mockito.ArgumentMatchers.nullable(String.class), anyString()))
                .thenReturn(new FairDataPointInstanceService.FetchResult(
                        org.springframework.http.ResponseEntity.ok()
                                .contentType(MediaType.valueOf("text/turtle"))
                                .body("@prefix dct: <http://purl.org/dc/terms/> ."),
                        FairDataPointInstanceService.FetchStatus.OK
                ));

        mvc.perform(get("/taniwha/fdp/dataset/barthel")
                        .contextPath("/taniwha")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "stratif.guttmann.tech")
                        .header("X-Forwarded-Port", "443"))
                .andExpect(status().isOk());

        verify(instanceService).fetchMetadata(
                eq("/dataset/barthel"),
                org.mockito.ArgumentMatchers.nullable(String.class),
                eq("https://stratif.guttmann.tech/taniwha/fdp")
        );
    }

    @Test
    void accessRoute_usesForwardedHeadersForPublicNodeAuthLinks() throws Exception {
        when(instanceService.buildAccessResponse(anyString(), eq("barthel")))
                .thenReturn(new FairDataPointAccessResponseDTO(
                        "barthel",
                        "barthel",
                        "Barthel.csv",
                        "Use node authorization",
                        "https://stratif.guttmann.tech/taniwha/node/authorize",
                        "https://stratif.guttmann.tech/taniwha/node/validate",
                        "https://stratif.guttmann.tech/taniwha/api/files/datasets/Barthel.csv"
                ));

        mvc.perform(get("/taniwha/fdp/access/barthel")
                        .contextPath("/taniwha")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "stratif.guttmann.tech")
                        .header("X-Forwarded-Port", "443"))
                .andExpect(status().isOk());

        verify(instanceService).buildAccessResponse(
                eq("https://stratif.guttmann.tech/taniwha/fdp"),
                eq("barthel")
        );
    }

    @Test
    void metadataRoutes_preferConfiguredPublicNodeUrlWithoutForwardedHeaders() throws Exception {
        MockMvc configuredMvc = MockMvcBuilders
                .standaloneSetup(new FairDataPointController(syncService, instanceService, fairDataPointBrandingConfig, jwtTokenUtil, "https://stratif.guttmann.tech"))
                .build();

        when(instanceService.fetchMetadata(anyString(), org.mockito.ArgumentMatchers.nullable(String.class), anyString()))
                .thenReturn(new FairDataPointInstanceService.FetchResult(
                        org.springframework.http.ResponseEntity.ok()
                                .contentType(MediaType.valueOf("text/turtle"))
                                .body("@prefix dct: <http://purl.org/dc/terms/> ."),
                        FairDataPointInstanceService.FetchStatus.OK
                ));

        configuredMvc.perform(get("/taniwha/fdp/dataset/barthel")
                        .contextPath("/taniwha"))
                .andExpect(status().isOk());

        verify(instanceService).fetchMetadata(
                eq("/dataset/barthel"),
                org.mockito.ArgumentMatchers.nullable(String.class),
                eq("https://stratif.guttmann.tech/taniwha/fdp")
        );
    }

}
