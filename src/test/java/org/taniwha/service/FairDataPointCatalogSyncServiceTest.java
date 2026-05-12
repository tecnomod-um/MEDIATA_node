package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.*;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.taniwha.config.RestTemplateHolder;
import org.taniwha.model.FairDataPointSyncResult;
import org.taniwha.model.MetadataDocument;
import org.taniwha.util.FairDataPointMetadataUtil;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FairDataPointCatalogSyncServiceTest {

    private FileService fileService;
    private FairDataPointMetadataUtil metadataUtil;
    private RestTemplate restTemplate;
    private FairDataPointCatalogSyncService service;

    @BeforeEach
    void setUp() {
        fileService = mock(FileService.class);
        metadataUtil = mock(FairDataPointMetadataUtil.class);
        restTemplate = mock(RestTemplate.class);

        service = new FairDataPointCatalogSyncService(
                fileService,
                metadataUtil,
                new RestTemplateHolder(() -> restTemplate),
                RetryTemplate.builder().maxAttempts(1).build(),
                true,
                "http://fdp:8080",
                "http://fdp:8080",
                "albert.einstein@example.com",
                "password",
                "",
                "Node A"
        );
    }

    @Test
    void publishCatalogs_usesInstanceTokenFlowAndPublishesCatalogDatasetAndDistributionMetadata() {
        when(metadataUtil.generateManagedMetadataDocument()).thenReturn(
                new MetadataDocument("fairdatapoint-generated.ttl", "")
        );
        when(fileService.readAllMetadataDocuments()).thenReturn(List.of(
                new MetadataDocument("catalog.ttl", """
                        @prefix dcat: <http://www.w3.org/ns/dcat#> .
                        @prefix dct: <http://purl.org/dc/terms/> .
                        @prefix foaf: <http://xmlns.com/foaf/0.1/> .

                        <http://example.org/catalog> a dcat:Catalog ;
                            dct:title "Node Catalog" ;
                            dct:publisher [ a foaf:Agent ; foaf:name "TANIWHA" ] ;
                            dcat:dataset <http://example.org/dataset> .

                        <http://example.org/dataset> a dcat:Dataset ;
                            dct:title "Dataset" ;
                            dcat:theme <http://publications.europa.eu/resource/authority/data-theme/HEAL> ;
                            dcat:distribution <http://example.org/distribution> .

                        <http://example.org/distribution> a dcat:Distribution ;
                            dct:title "Distribution" ;
                            dcat:mediaType <https://www.iana.org/assignments/media-types/text/csv> ;
                            dcat:downloadURL <http://node.example/taniwha/api/files/datasets/example.csv> .
                        """)
        ));

        when(restTemplate.postForObject(eq("http://fdp:8080/tokens"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(Map.of("token", "service-token"));
        when(restTemplate.exchange(eq("http://fdp:8080/"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("root"));
        when(restTemplate.exchange(eq("http://fdp:8080/reset"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());
        when(restTemplate.exchange(eq("http://fdp:8080/catalog"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.created(URI.create("http://fdp:8080/catalog/123")).body(""));
        when(restTemplate.exchange(eq("http://fdp:8080/dataset"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.created(URI.create("http://fdp:8080/dataset/456")).body(""));
        when(restTemplate.exchange(eq("http://fdp:8080/distribution"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.created(URI.create("http://fdp:8080/distribution/789")).body(""));
        when(restTemplate.exchange(eq("http://fdp:8080/catalog/123/meta/state"), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());
        when(restTemplate.exchange(eq("http://fdp:8080/dataset/456/meta/state"), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());
        when(restTemplate.exchange(eq("http://fdp:8080/distribution/789/meta/state"), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());

        FairDataPointSyncResult result = service.publishCatalogs();

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.metadataFilesRead()).isEqualTo(1);
        assertThat(result.catalogsPublished()).isEqualTo(1);
        assertThat(result.datasetsPublished()).isEqualTo(1);
        assertThat(result.distributionsPublished()).isEqualTo(1);
        assertThat(result.publishedCatalogUris()).containsExactly("http://fdp:8080/catalog/123");
        assertThat(result.publishedDatasetUris()).containsExactly("http://fdp:8080/dataset/456");
        assertThat(result.publishedDistributionUris()).containsExactly("http://fdp:8080/distribution/789");

        ArgumentCaptor<HttpEntity> postCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("http://fdp:8080/catalog"), eq(HttpMethod.POST), postCaptor.capture(), eq(String.class));
        HttpEntity<?> catalogEntity = postCaptor.getValue();
        String body = String.valueOf(catalogEntity.getBody());

        assertThat(catalogEntity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer service-token");
        assertThat(catalogEntity.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("text/turtle"));
        assertThat(body).contains("Node Catalog");
        assertThat(body).doesNotContain("Dataset");
        assertThat(body).contains("hasVersion");
        assertThat(body).contains("isPartOf");
        assertThat(body).contains("<>");
        assertThat(body).contains("dcat:Catalog");
        assertThat(body).doesNotContain("http://fdp:8080/catalog/node-catalog");

        ArgumentCaptor<HttpEntity> datasetCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("http://fdp:8080/dataset"), eq(HttpMethod.POST), datasetCaptor.capture(), eq(String.class));
        String datasetBody = String.valueOf(datasetCaptor.getValue().getBody());
        assertThat(datasetBody).contains("Dataset");
        assertThat(datasetBody).contains("http://fdp:8080/catalog/123");
        assertThat(datasetBody).doesNotContain("Distribution");
        assertThat(datasetBody).contains("<>");
        assertThat(datasetBody).contains("dcat:Dataset");

        ArgumentCaptor<HttpEntity> distributionCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("http://fdp:8080/distribution"), eq(HttpMethod.POST), distributionCaptor.capture(), eq(String.class));
        String distributionBody = String.valueOf(distributionCaptor.getValue().getBody());
        assertThat(distributionBody).contains("Distribution");
        assertThat(distributionBody).contains("http://fdp:8080/dataset/456");
        assertThat(distributionBody).contains("text/csv");
        assertThat(distributionBody).contains("<>");
        assertThat(distributionBody).contains("dcat:Distribution");

        ArgumentCaptor<HttpEntity> stateCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("http://fdp:8080/catalog/123/meta/state"), eq(HttpMethod.PUT), stateCaptor.capture(), eq(Void.class));
        HttpEntity<?> stateEntity = stateCaptor.getValue();
        assertThat(stateEntity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer service-token");
        assertThat(String.valueOf(stateEntity.getBody())).contains("PUBLISHED");

        ArgumentCaptor<HttpEntity> resetCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("http://fdp:8080/reset"), eq(HttpMethod.POST), resetCaptor.capture(), eq(Void.class));
        @SuppressWarnings("unchecked")
        Map<String, Boolean> resetBody = (Map<String, Boolean>) resetCaptor.getValue().getBody();
        assertThat(resetBody).containsEntry("metadata", true);
        assertThat(resetBody).containsEntry("resourceDefinitions", false);
    }

    @Test
    void publishCatalogs_withApiKey_skipsTokenRequest() {
        service = new FairDataPointCatalogSyncService(
                fileService,
                metadataUtil,
                new RestTemplateHolder(() -> restTemplate),
                RetryTemplate.builder().maxAttempts(1).build(),
                true,
                "http://fdp:8080",
                "http://fdp:8080",
                "",
                "",
                "internal-api-key",
                "Node A"
        );

        when(metadataUtil.generateManagedMetadataDocument()).thenReturn(
                new MetadataDocument("fairdatapoint-generated.ttl", "")
        );
        when(fileService.readAllMetadataDocuments()).thenReturn(List.of(
                new MetadataDocument("catalog.ttl", """
                        @prefix dcat: <http://www.w3.org/ns/dcat#> .
                        <http://example.org/catalog> a dcat:Catalog .
                        """)
        ));
        when(restTemplate.exchange(eq("http://fdp:8080/"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("root"));
        when(restTemplate.exchange(eq("http://fdp:8080/reset"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());
        when(restTemplate.exchange(eq("http://fdp:8080/catalog"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.created(URI.create("http://fdp:8080/catalog/abc")).body(""));
        when(restTemplate.exchange(eq("http://fdp:8080/catalog/abc/meta/state"), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());

        FairDataPointSyncResult result = service.publishCatalogs();

        assertThat(result.catalogsPublished()).isEqualTo(1);
        verify(restTemplate, never()).postForObject(eq("http://fdp:8080/tokens"), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    void publishCatalogs_withoutConfiguredCredentials_usesDefaultFdpCredentials() {
        service = new FairDataPointCatalogSyncService(
                fileService,
                metadataUtil,
                new RestTemplateHolder(() -> restTemplate),
                RetryTemplate.builder().maxAttempts(1).build(),
                true,
                "http://fdp:8080",
                "http://fdp:8080",
                "",
                "",
                "",
                "Node A"
        );

        when(metadataUtil.generateManagedMetadataDocument()).thenReturn(
                new MetadataDocument("fairdatapoint-generated.ttl", "")
        );
        when(fileService.readAllMetadataDocuments()).thenReturn(List.of(
                new MetadataDocument("catalog.ttl", """
                        @prefix dcat: <http://www.w3.org/ns/dcat#> .
                        <http://example.org/catalog> a dcat:Catalog .
                        """)
        ));
        when(restTemplate.postForObject(eq("http://fdp:8080/tokens"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(Map.of("token", "service-token"));
        when(restTemplate.exchange(eq("http://fdp:8080/"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("root"));
        when(restTemplate.exchange(eq("http://fdp:8080/reset"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());
        when(restTemplate.exchange(eq("http://fdp:8080/catalog"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.created(URI.create("http://fdp:8080/catalog/abc")).body(""));
        when(restTemplate.exchange(eq("http://fdp:8080/catalog/abc/meta/state"), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());

        FairDataPointSyncResult result = service.publishCatalogs();

        assertThat(result.catalogsPublished()).isEqualTo(1);

        ArgumentCaptor<HttpEntity> tokenCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(eq("http://fdp:8080/tokens"), tokenCaptor.capture(), eq(Map.class));
        @SuppressWarnings("unchecked")
        Map<String, String> tokenBody = (Map<String, String>) tokenCaptor.getValue().getBody();
        assertThat(tokenBody).containsEntry("email", "albert.einstein@example.com");
        assertThat(tokenBody).containsEntry("password", "password");
    }

    @Test
    void publishCatalogs_whenPersistentUrlDiffers_usesBaseUrlForPublishStateChange() {
        service = new FairDataPointCatalogSyncService(
                fileService,
                metadataUtil,
                new RestTemplateHolder(() -> restTemplate),
                RetryTemplate.builder().maxAttempts(1).build(),
                true,
                "http://127.0.0.1:18180",
                "http://fdp",
                "albert.einstein@example.com",
                "password",
                "",
                "Node A"
        );

        when(metadataUtil.generateManagedMetadataDocument()).thenReturn(
                new MetadataDocument("fairdatapoint-generated.ttl", "")
        );
        when(fileService.readAllMetadataDocuments()).thenReturn(List.of(
                new MetadataDocument("catalog.ttl", """
                        @prefix dcat: <http://www.w3.org/ns/dcat#> .
                        <http://example.org/catalog> a dcat:Catalog .
                        """)
        ));

        when(restTemplate.postForObject(eq("http://127.0.0.1:18180/tokens"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(Map.of("token", "service-token"));
        when(restTemplate.exchange(eq("http://127.0.0.1:18180/"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("root"));
        when(restTemplate.exchange(eq("http://127.0.0.1:18180/reset"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());
        when(restTemplate.exchange(eq("http://127.0.0.1:18180/catalog"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.created(URI.create("http://fdp/catalog/abc")).body(""));
        when(restTemplate.exchange(eq("http://127.0.0.1:18180/catalog/abc/meta/state"), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());

        FairDataPointSyncResult result = service.publishCatalogs();

        assertThat(result.publishedCatalogUris()).containsExactly("http://fdp/catalog/abc");
        verify(restTemplate).exchange(eq("http://127.0.0.1:18180/catalog/abc/meta/state"), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class));
        verify(restTemplate, never()).exchange(eq("http://fdp/catalog/abc/meta/state"), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class));
    }

    @Test
    void publishCatalogs_copiesNamedAgentAndContactPointClosuresIntoPublishedPayloads() {
        when(metadataUtil.generateManagedMetadataDocument()).thenReturn(
                new MetadataDocument("fairdatapoint-generated.ttl", "")
        );
        when(fileService.readAllMetadataDocuments()).thenReturn(List.of(
                new MetadataDocument("catalog.ttl", """
                        @prefix dcat: <http://www.w3.org/ns/dcat#> .
                        @prefix dct: <http://purl.org/dc/terms/> .
                        @prefix foaf: <http://xmlns.com/foaf/0.1/> .
                        @prefix vcard: <http://www.w3.org/2006/vcard/ns#> .
                        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

                        <http://example.org/catalog> a dcat:Catalog ;
                            dct:title "Node Catalog" ;
                            dct:publisher <http://example.org/org/node> ;
                            dcat:dataset <http://example.org/dataset> .

                        <http://example.org/dataset> a dcat:Dataset ;
                            dct:title "Dataset" ;
                            dct:publisher <http://example.org/org/node> ;
                            dct:creator <http://example.org/org/partner> ;
                            dct:issued "2025-02-13"^^xsd:date ;
                            dcat:contactPoint <http://example.org/contact/data> ;
                            dcat:distribution <http://example.org/distribution> .

                        <http://example.org/distribution> a dcat:Distribution ;
                            dct:title "Distribution" ;
                            dct:publisher <http://example.org/org/node> ;
                            dcat:contactPoint <http://example.org/contact/data> .

                        <http://example.org/org/node> a foaf:Organization ;
                            foaf:name "Node Org" .

                        <http://example.org/org/partner> a foaf:Organization ;
                            foaf:name "Partner Org" .

                        <http://example.org/contact/data> a vcard:Individual ;
                            vcard:fn "Data Office" .
                        """)
        ));

        when(restTemplate.postForObject(eq("http://fdp:8080/tokens"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(Map.of("token", "service-token"));
        when(restTemplate.exchange(eq("http://fdp:8080/"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("root"));
        when(restTemplate.exchange(eq("http://fdp:8080/reset"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());
        when(restTemplate.exchange(eq("http://fdp:8080/catalog"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.created(URI.create("http://fdp:8080/catalog/123")).body(""));
        when(restTemplate.exchange(eq("http://fdp:8080/dataset"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.created(URI.create("http://fdp:8080/dataset/456")).body(""));
        when(restTemplate.exchange(eq("http://fdp:8080/distribution"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.created(URI.create("http://fdp:8080/distribution/789")).body(""));
        when(restTemplate.exchange(eq("http://fdp:8080/catalog/123/meta/state"), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());
        when(restTemplate.exchange(eq("http://fdp:8080/dataset/456/meta/state"), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());
        when(restTemplate.exchange(eq("http://fdp:8080/distribution/789/meta/state"), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());

        service.publishCatalogs();

        ArgumentCaptor<HttpEntity> catalogCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("http://fdp:8080/catalog"), eq(HttpMethod.POST), catalogCaptor.capture(), eq(String.class));
        assertThat(String.valueOf(catalogCaptor.getValue().getBody())).contains("Node Org");

        ArgumentCaptor<HttpEntity> datasetCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("http://fdp:8080/dataset"), eq(HttpMethod.POST), datasetCaptor.capture(), eq(String.class));
        String datasetBody = String.valueOf(datasetCaptor.getValue().getBody());
        assertThat(datasetBody).contains("Node Org");
        assertThat(datasetBody).contains("Partner Org");
        assertThat(datasetBody).contains("Data Office");
        assertThat(datasetBody).contains("2025-02-13T00:00:00Z");
        assertThat(countOccurrences(datasetBody, "dct:publisher")).isEqualTo(1);

        ArgumentCaptor<HttpEntity> distributionCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("http://fdp:8080/distribution"), eq(HttpMethod.POST), distributionCaptor.capture(), eq(String.class));
        assertThat(String.valueOf(distributionCaptor.getValue().getBody())).contains("Data Office");
    }

    @Test
    void publishCatalogs_whenInstanceRootMetadataIsMissing_returnsHelpfulError() {
        when(metadataUtil.generateManagedMetadataDocument()).thenReturn(
                new MetadataDocument("fairdatapoint-generated.ttl", "")
        );
        when(fileService.readAllMetadataDocuments()).thenReturn(List.of(
                new MetadataDocument("catalog.ttl", """
                        @prefix dcat: <http://www.w3.org/ns/dcat#> .
                        <http://example.org/catalog> a dcat:Catalog .
                        """)
        ));

        when(restTemplate.postForObject(eq("http://fdp:8080/tokens"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(Map.of("token", "service-token"));
        when(restTemplate.exchange(eq("http://fdp:8080/"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.publishCatalogs())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("root metadata is not initialized");
    }

    @Test
    void publishCatalogs_whenNoMetadataFilesExist_returnsNoMetadataSummary() {
        when(metadataUtil.generateManagedMetadataDocument()).thenReturn(
                new MetadataDocument("fairdatapoint-generated.ttl", "")
        );
        when(fileService.readAllMetadataDocuments()).thenReturn(List.of());

        FairDataPointSyncResult result = service.publishCatalogs();

        assertThat(result.status()).isEqualTo("NO_METADATA");
        assertThat(result.metadataFilesRead()).isZero();
        assertThat(result.catalogsPublished()).isZero();
        assertThat(result.datasetsPublished()).isZero();
        assertThat(result.distributionsPublished()).isZero();
        verifyNoInteractions(restTemplate);
    }

    @Test
    void publishCatalogs_whenPublicationFails_resetsAgainToCleanPartialState() {
        when(metadataUtil.generateManagedMetadataDocument()).thenReturn(
                new MetadataDocument("fairdatapoint-generated.ttl", "")
        );
        when(fileService.readAllMetadataDocuments()).thenReturn(List.of(
                new MetadataDocument("catalog.ttl", """
                        @prefix dcat: <http://www.w3.org/ns/dcat#> .
                        @prefix dct: <http://purl.org/dc/terms/> .

                        <http://example.org/catalog> a dcat:Catalog ;
                            dct:title "Node Catalog" ;
                            dcat:dataset <http://example.org/dataset> .

                        <http://example.org/dataset> a dcat:Dataset ;
                            dct:title "Dataset" .
                        """)
        ));

        when(restTemplate.postForObject(eq("http://fdp:8080/tokens"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(Map.of("token", "service-token"));
        when(restTemplate.exchange(eq("http://fdp:8080/"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("root"));
        when(restTemplate.exchange(eq("http://fdp:8080/reset"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());
        when(restTemplate.exchange(eq("http://fdp:8080/catalog"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.created(URI.create("http://fdp:8080/catalog/123")).body(""));
        when(restTemplate.exchange(eq("http://fdp:8080/catalog/123/meta/state"), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());
        when(restTemplate.exchange(eq("http://fdp:8080/dataset"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.publishCatalogs())
                .isInstanceOf(HttpClientErrorException.BadRequest.class);

        verify(restTemplate, times(2))
                .exchange(eq("http://fdp:8080/reset"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class));
    }

    private int countOccurrences(String value, String token) {
        AtomicInteger count = new AtomicInteger();
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count.incrementAndGet();
            index += token.length();
        }
        return count.get();
    }
}
