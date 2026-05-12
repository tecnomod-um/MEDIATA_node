package org.taniwha.config;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestTemplate;

import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FairDataPointBrandingConfigTest {

    private static final String ROOT_URI = "http://127.0.0.1:18080";
    private static final String ROOT_TURTLE = """
            @prefix dcterms: <http://purl.org/dc/terms/> .
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .
            @prefix fdp-o: <https://w3id.org/fdp/fdp-o#> .
            @prefix sio: <http://semanticscience.org/resource/> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

            <http://127.0.0.1:18080> a dcat:Resource, dcat:DataService, fdp-o:MetadataService, fdp-o:FAIRDataPoint;
              dcterms:title "My FAIR Data Point";
              rdfs:label "Catalogs";
              dcterms:description "Default description";
              dcterms:conformsTo <http://127.0.0.1:18080/profile/abc>;
              dcat:endpointURL <http://127.0.0.1:18080>;
              fdp-o:fdpSoftwareVersion "FDP:v1.16.2";
              sio:SIO_000628 <http://127.0.0.1:18080/metrics/abc>;
              dcterms:publisher <http://127.0.0.1:18080#publisher>.

            <http://127.0.0.1:18080#publisher> a foaf:Agent;
              foaf:name "Default Publisher" .
            """;

    @Test
    void brandRootMetadata_updatesBrandingWithoutKeepingGeneratedRootStatements() {
        FairDataPointBrandingConfig config = new FairDataPointBrandingConfig(
                mock(RestTemplateHolder.class),
                new RetryTemplate(),
                true,
                "http://127.0.0.1:18080",
                "",
                "",
                "",
                "Mediata_local",
                "UMU MEDIATA server",
                "",
                "",
                ""
        );

        String branded = config.brandRootMetadata(ROOT_TURTLE);

        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new StringReader(branded), null, Lang.TURTLE);

        Resource root = model.getResource(ROOT_URI);
        Property titleProperty = model.createProperty("http://purl.org/dc/terms/title");
        Property descriptionProperty = model.createProperty("http://purl.org/dc/terms/description");
        Property publisherProperty = model.createProperty("http://purl.org/dc/terms/publisher");
        Property nameProperty = model.createProperty("http://xmlns.com/foaf/0.1/name");

        assertTrue(root.hasProperty(titleProperty, "Mediata_local FAIR Data Point"));
        assertTrue(root.hasProperty(descriptionProperty,
                "The MEDIATA platform provides a unified, browser-accessible interface for the secure exploration, " +
                        "cleaning, harmonization, and semantic annotation of distributed clinical datasets while keeping " +
                        "sensitive raw data on local servers."));
        assertFalse(root.hasProperty(model.createProperty("http://purl.org/dc/terms/conformsTo")));
        assertFalse(root.hasProperty(model.createProperty("http://www.w3.org/ns/dcat#endpointURL")));
        assertFalse(root.hasProperty(model.createProperty("https://w3id.org/fdp/fdp-o#fdpSoftwareVersion")));
        assertFalse(root.hasProperty(model.createProperty("http://semanticscience.org/resource/SIO_000628")));
        assertFalse(root.hasProperty(model.createProperty("http://www.w3.org/2000/01/rdf-schema#label")));

        Resource publisher = root.getPropertyResourceValue(publisherProperty);
        assertTrue(publisher.hasProperty(nameProperty, "Mediata_local"));
    }

    @Test
    void applyBranding_usesApiKeyWithoutRequestingToken() {
        RestTemplateHolder holder = mock(RestTemplateHolder.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(holder.get()).thenReturn(restTemplate);
        when(restTemplate.exchange(
                "http://127.0.0.1:18080/",
                HttpMethod.GET,
                new HttpEntity<>(headersWithAcceptOnly()),
                String.class
        )).thenReturn(ResponseEntity.ok(ROOT_TURTLE));
        when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.eq("http://127.0.0.1:18080/"),
                org.mockito.ArgumentMatchers.eq(HttpMethod.PUT),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>any(),
                org.mockito.ArgumentMatchers.eq(Void.class)
        )).thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

        FairDataPointBrandingConfig config = new FairDataPointBrandingConfig(
                holder,
                new RetryTemplate(),
                true,
                "http://127.0.0.1:18080/",
                "",
                "",
                "api-token",
                "Mediata_local",
                "UMU MEDIATA server",
                "",
                "",
                ""
        );

        config.applyBranding();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<String>> putCaptor = ArgumentCaptor.forClass((Class) HttpEntity.class);
        verify(restTemplate).exchange(
                org.mockito.ArgumentMatchers.eq("http://127.0.0.1:18080/"),
                org.mockito.ArgumentMatchers.eq(HttpMethod.PUT),
                putCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(Void.class)
        );
        verify(restTemplate, never()).postForObject(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(Map.class)
        );

        HttpEntity<String> putEntity = putCaptor.getValue();
        assertEquals("Bearer api-token", putEntity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        assertTrue(putEntity.getBody().contains("Mediata_local FAIR Data Point"));
    }

    @Test
    void applyBranding_usesDefaultCredentialsWhenEmailAndPasswordAreBlank() {
        RestTemplateHolder holder = mock(RestTemplateHolder.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(holder.get()).thenReturn(restTemplate);
        when(restTemplate.postForObject(
                org.mockito.ArgumentMatchers.eq("http://127.0.0.1:18080/tokens"),
                org.mockito.ArgumentMatchers.<HttpEntity<Map<String, String>>>any(),
                org.mockito.ArgumentMatchers.eq(Map.class)
        )).thenReturn(Map.of("token", "resolved-token"));
        when(restTemplate.exchange(
                "http://127.0.0.1:18080/",
                HttpMethod.GET,
                new HttpEntity<>(headersWithAcceptOnly()),
                String.class
        )).thenReturn(ResponseEntity.ok(ROOT_TURTLE));
        when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.eq("http://127.0.0.1:18080/"),
                org.mockito.ArgumentMatchers.eq(HttpMethod.PUT),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>any(),
                org.mockito.ArgumentMatchers.eq(Void.class)
        )).thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

        FairDataPointBrandingConfig config = new FairDataPointBrandingConfig(
                holder,
                new RetryTemplate(),
                true,
                "http://127.0.0.1:18080",
                "",
                "",
                "",
                "Mediata_local",
                "UMU MEDIATA server",
                "",
                "",
                ""
        );

        config.applyBranding();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, String>>> tokenCaptor = ArgumentCaptor.forClass((Class) HttpEntity.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<String>> putCaptor = ArgumentCaptor.forClass((Class) HttpEntity.class);

        verify(restTemplate).postForObject(
                org.mockito.ArgumentMatchers.eq("http://127.0.0.1:18080/tokens"),
                tokenCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(Map.class)
        );
        verify(restTemplate).exchange(
                org.mockito.ArgumentMatchers.eq("http://127.0.0.1:18080/"),
                org.mockito.ArgumentMatchers.eq(HttpMethod.PUT),
                putCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(Void.class)
        );

        assertEquals("albert.einstein@example.com", tokenCaptor.getValue().getBody().get("email"));
        assertEquals("password", tokenCaptor.getValue().getBody().get("password"));
        assertEquals("Bearer resolved-token", putCaptor.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void applyBranding_rejectsDisabledIntegration() {
        FairDataPointBrandingConfig config = new FairDataPointBrandingConfig(
                mock(RestTemplateHolder.class),
                new RetryTemplate(),
                false,
                "http://127.0.0.1:18080",
                "",
                "",
                "",
                "Mediata_local",
                "UMU MEDIATA server",
                "",
                "",
                ""
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class, config::applyBranding);
        assertEquals("FAIR Data Point integration is disabled", exception.getMessage());
    }

    @Test
    void brandRootMetadata_rejectsMetadataWithoutRecognizedRootResource() {
        FairDataPointBrandingConfig config = new FairDataPointBrandingConfig(
                mock(RestTemplateHolder.class),
                new RetryTemplate(),
                true,
                "http://127.0.0.1:18080",
                "",
                "",
                "",
                "Mediata_local",
                "UMU MEDIATA server",
                "",
                "",
                ""
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> config.brandRootMetadata("@prefix dcterms: <http://purl.org/dc/terms/> . <http://example.test/other> dcterms:title \"No root\" ."));
        assertEquals("Unable to locate FAIR Data Point root resource", exception.getMessage());
    }

    private HttpHeaders headersWithAcceptOnly() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(org.springframework.http.MediaType.parseMediaType("text/turtle")));
        return headers;
    }
}
