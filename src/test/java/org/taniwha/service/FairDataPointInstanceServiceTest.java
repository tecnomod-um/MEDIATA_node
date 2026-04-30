package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.taniwha.config.RestTemplateHolder;
import org.taniwha.dto.FairDataPointAccessResponseDTO;
import org.taniwha.security.FileFilter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FairDataPointInstanceServiceTest {

    @Mock
    private FileFilter fileFilter;

    @TempDir
    Path tempBase;

    private RestTemplate restTemplate;
    private FileService fileService;
    private FairDataPointInstanceService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        doNothing().when(fileFilter).validate(any(Path.class));
        fileService = new FileService(fileFilter, tempBase.toString());
        restTemplate = mock(RestTemplate.class);
        RestTemplateHolder holder = new RestTemplateHolder(new Supplier<>() {
            @Override
            public RestTemplate get() {
                return restTemplate;
            }
        });
        service = new FairDataPointInstanceService(
                holder,
                fileService,
                true,
                "http://127.0.0.1:18180",
                "http://fdp"
        );
    }

    private FairDataPointInstanceService newService(boolean enabled, String baseUrl, String persistentUrl) {
        return new FairDataPointInstanceService(
                new RestTemplateHolder(new Supplier<>() {
                    @Override
                    public RestTemplate get() {
                        return restTemplate;
                    }
                }),
                fileService,
                enabled,
                baseUrl,
                persistentUrl
        );
    }

    @Test
    void fetchMetadata_rewritesInstanceUrisToLocalFacadeUris() {
        when(restTemplate.exchange(
                eq("http://127.0.0.1:18180/"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/turtle"))
                .body("<http://fdp> <http://www.w3.org/ns/ldp#contains> <http://fdp/catalog/123> ."));

        FairDataPointInstanceService.FetchResult result = service.fetchMetadata("/", null, "http://example.test/taniwha/fdp");
        ResponseEntity<String> response = result.response();

        assertThat(response).isNotNull();
        assertThat(result.status()).isEqualTo(FairDataPointInstanceService.FetchStatus.OK);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("text/turtle"));
        assertThat(response.getBody()).contains("http://example.test/taniwha/fdp/catalog/123");
        assertThat(response.getBody()).doesNotContain("http://fdp/catalog/123");
    }

        @Test
        void fetchMetadata_disabledService_returnsDisabledStatus() {
                FairDataPointInstanceService disabledService = newService(false, "http://127.0.0.1:18180", "http://fdp");

                FairDataPointInstanceService.FetchResult result = disabledService.fetchMetadata("/", "text/turtle", "http://example.test/taniwha/fdp");

                assertThat(result.response()).isNull();
                assertThat(result.status()).isEqualTo(FairDataPointInstanceService.FetchStatus.DISABLED);
        }

    @Test
    void fetchMetadata_defaultsWildcardAcceptRequestsToTurtle() {
        when(restTemplate.exchange(
                eq("http://127.0.0.1:18180/"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/turtle"))
                .body("<http://fdp> <http://purl.org/dc/terms/title> \"Example\" ."));

        service.fetchMetadata("/", "*/*", "http://example.test/taniwha/fdp");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<String>> entityCaptor = ArgumentCaptor.forClass((Class) HttpEntity.class);
        verify(restTemplate).exchange(eq("http://127.0.0.1:18180/"), eq(HttpMethod.GET), entityCaptor.capture(), eq(String.class));
        List<MediaType> accepted = entityCaptor.getValue().getHeaders().getAccept();
        assertThat(accepted).containsExactly(MediaType.valueOf("text/turtle"));
    }

    @Test
    void fetchMetadata_invalidAcceptHeaderFallsBackToTurtle() {
        when(restTemplate.exchange(
                eq("http://127.0.0.1:18180/"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/turtle"))
                .body("<http://fdp> <http://purl.org/dc/terms/title> \"Example\" ."));

        service.fetchMetadata("/", "not-a-media-type", "http://example.test/taniwha/fdp");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<String>> entityCaptor = ArgumentCaptor.forClass((Class) HttpEntity.class);
        verify(restTemplate).exchange(eq("http://127.0.0.1:18180/"), eq(HttpMethod.GET), entityCaptor.capture(), eq(String.class));
        assertThat(entityCaptor.getValue().getHeaders().getAccept()).containsExactly(MediaType.valueOf("text/turtle"));
    }

    @Test
    void fetchMetadata_returnsNullWhenInstanceResourceDoesNotExist() {
        when(restTemplate.exchange(
                eq("http://127.0.0.1:18180/dataset/missing"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenThrow(HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Not Found",
                org.springframework.http.HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        ));

        FairDataPointInstanceService.FetchResult response = service.fetchMetadata("/dataset/missing", "text/turtle", "http://example.test/taniwha/fdp");

        assertThat(response.response()).isNull();
        assertThat(response.status()).isEqualTo(FairDataPointInstanceService.FetchStatus.NOT_FOUND);
    }

    @Test
    void fetchMetadata_mapsMetadataSchemaRequestsToRootSpecEndpoint() {
        when(restTemplate.exchange(
                eq("http://127.0.0.1:18180/spec"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/turtle"))
                .body("<http://fdp/profile/1> <http://www.w3.org/ns/dx/prof/hasArtifact> <http://fdp/metadata-schemas/a92958ab-a414-47e6-8e17-68ba96ba3a2b> ."));

        FairDataPointInstanceService.FetchResult result = service.fetchMetadata(
                "/metadata-schemas/a92958ab-a414-47e6-8e17-68ba96ba3a2b",
                "text/turtle",
                "http://example.test/taniwha/fdp"
        );

        assertThat(result.status()).isEqualTo(FairDataPointInstanceService.FetchStatus.OK);
        assertThat(result.response()).isNotNull();
        assertThat(result.response().getBody()).contains("http://example.test/taniwha/fdp/spec");
    }

    @Test
    void fetchMetadata_preservesInstanceMethodNotAllowedResponses() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        when(restTemplate.exchange(
                eq("http://127.0.0.1:18180/catalog/"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenThrow(HttpClientErrorException.create(
                org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED,
                "Method Not Allowed",
                headers,
                "{\"status\":405}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        ));

        FairDataPointInstanceService.FetchResult result = service.fetchMetadata("/catalog/", "text/turtle", "http://example.test/taniwha/fdp");

        assertThat(result.status()).isEqualTo(FairDataPointInstanceService.FetchStatus.OK);
        assertThat(result.response()).isNotNull();
        assertThat(result.response().getStatusCode().value()).isEqualTo(405);
        assertThat(result.response().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(result.response().getBody()).contains("\"status\":405");
    }

    @Test
    void buildAccessResponse_usesNodeAuthorizationFlow() throws Exception {
        Files.createDirectories(tempBase.resolve("datasets"));
        Files.writeString(tempBase.resolve("datasets/FIM.csv"), "score\n90\n");

        FairDataPointAccessResponseDTO response = service.buildAccessResponse("http://example.test/taniwha/fdp", "fim");

        assertThat(response).isNotNull();
        assertThat(response.getAuthorizeEndpoint()).isEqualTo("http://example.test/taniwha/node/authorize");
        assertThat(response.getValidateEndpoint()).isEqualTo("http://example.test/taniwha/node/validate");
        assertThat(response.getDatasetDownloadEndpoint()).isEqualTo("http://example.test/taniwha/api/files/datasets/FIM.csv");
    }

        @Test
        void buildAccessResponse_withoutFdpSuffix_keepsBaseUrlIntact() throws Exception {
                Files.createDirectories(tempBase.resolve("datasets"));
                Files.writeString(tempBase.resolve("datasets/FIM.csv"), "score\n90\n");

                FairDataPointAccessResponseDTO response = service.buildAccessResponse("http://example.test/taniwha", "fim");

                assertThat(response.getAuthorizeEndpoint()).isEqualTo("http://example.test/taniwha/node/authorize");
                assertThat(response.getValidateEndpoint()).isEqualTo("http://example.test/taniwha/node/validate");
        }

    @Test
    void buildAccessResponse_returnsNullForUnknownDistribution() {
        FairDataPointAccessResponseDTO response = service.buildAccessResponse("http://example.test/taniwha/fdp", "missing");

        assertThat(response).isNull();
    }
}
