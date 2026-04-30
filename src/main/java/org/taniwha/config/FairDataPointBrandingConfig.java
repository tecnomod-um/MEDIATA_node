package org.taniwha.config;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

@Component
public class FairDataPointBrandingConfig {

    private static final Logger logger = LoggerFactory.getLogger(FairDataPointBrandingConfig.class);
    private static final String DCAT = "http://www.w3.org/ns/dcat#";
    private static final String DCT = "http://purl.org/dc/terms/";
    private static final String FOAF = "http://xmlns.com/foaf/0.1/";
    private static final String FDP_O = "https://w3id.org/fdp/fdp-o#";
    private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String SIO = "http://semanticscience.org/resource/";
    private static final String DEFAULT_FDP_EMAIL = "albert.einstein@example.com";
    private static final String DEFAULT_FDP_PASSWORD = "password";
    private static final MediaType TURTLE = MediaType.parseMediaType("text/turtle");

    private final RestTemplateHolder restTemplateHolder;
    private final RetryTemplate retryTemplate;
    private final boolean enabled;
    private final String baseUrl;
    private final String email;
    private final String password;
    private final String apiKey;
    private final String nodeName;
    private final String nodeDescription;

    public FairDataPointBrandingConfig(RestTemplateHolder restTemplateHolder,
                                       RetryTemplate retryTemplate,
                                       @Value("${fairdatapoint.enabled:false}") boolean enabled,
                                       @Value("${fairdatapoint.base-url:}") String baseUrl,
                                       @Value("${fairdatapoint.email:}") String email,
                                       @Value("${fairdatapoint.password:}") String password,
                                       @Value("${fairdatapoint.api-key:}") String apiKey,
                                       @Value("${name:unnamed}") String nodeName,
                                       @Value("${desc:no description available}") String nodeDescription) {
        this.restTemplateHolder = restTemplateHolder;
        this.retryTemplate = retryTemplate;
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.email = email == null ? "" : email.trim();
        this.password = password == null ? "" : password;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.nodeName = nodeName == null || nodeName.isBlank() ? "unnamed" : nodeName.trim();
        this.nodeDescription = nodeDescription == null || nodeDescription.isBlank()
                ? "no description available"
                : nodeDescription.trim();
    }

    public void applyBranding() {
        ensureEnabled();

        String bearerToken = resolveBearerToken();
        ResponseEntity<String> response = retryTemplate.execute((RetryCallback<ResponseEntity<String>, RestClientException>) context -> {
            if (context.getRetryCount() > 0) {
                logger.warn("Retrying FAIR Data Point branding fetch. Attempt {}", context.getRetryCount() + 1);
            }
            return restTemplateHolder.get().exchange(
                    normalizeBaseUrl(baseUrl) + "/",
                    HttpMethod.GET,
                    new HttpEntity<>(acceptTurtleHeaders()),
                    String.class
            );
        });

        String rootTurtle = response.getBody();
        if (rootTurtle == null || rootTurtle.isBlank()) {
            throw new IllegalStateException("FAIR Data Point root metadata is empty");
        }

        String brandedTurtle = brandRootMetadata(rootTurtle);
        ResponseEntity<Void> updateResponse = retryTemplate.execute((RetryCallback<ResponseEntity<Void>, RestClientException>) context -> {
            if (context.getRetryCount() > 0) {
                logger.warn("Retrying FAIR Data Point branding update. Attempt {}", context.getRetryCount() + 1);
            }
            return restTemplateHolder.get().exchange(
                    normalizeBaseUrl(baseUrl) + "/",
                    HttpMethod.PUT,
                    new HttpEntity<>(brandedTurtle, brandHeaders(bearerToken)),
                    Void.class
            );
        });

        if (updateResponse.getStatusCode().is2xxSuccessful()) {
            logger.info("Applied FAIR Data Point branding for {}", nodeName);
            return;
        }

        throw new IllegalStateException("FAIR Data Point branding update failed with HTTP " + updateResponse.getStatusCode().value());
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new IllegalStateException("FAIR Data Point integration is disabled");
        }
        if (isBlank(baseUrl)) {
            throw new IllegalStateException("FAIR Data Point base URL is not configured");
        }
    }

    private String resolveBearerToken() {
        if (!isBlank(apiKey)) {
            return apiKey;
        }

        String username = isBlank(email) ? DEFAULT_FDP_EMAIL : email;
        String secret = isBlank(password) ? DEFAULT_FDP_PASSWORD : password;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        Map<String, String> body = Map.of(
                "email", username,
                "password", secret
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> tokenResponse = retryTemplate.execute((RetryCallback<Map<String, Object>, RestClientException>) context -> {
            if (context.getRetryCount() > 0) {
                logger.warn("Retrying FAIR Data Point token request. Attempt {}", context.getRetryCount() + 1);
            }
            RestTemplate restTemplate = restTemplateHolder.get();
            return restTemplate.postForObject(normalizeBaseUrl(baseUrl) + "/tokens", new HttpEntity<>(body, headers), Map.class);
        });

        if (tokenResponse == null || !(tokenResponse.get("token") instanceof String token) || token.isBlank()) {
            throw new IllegalStateException("FAIR Data Point token endpoint did not return a valid token");
        }

        return token;
    }

    String brandRootMetadata(String rootTurtle) {
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new StringReader(rootTurtle), null, Lang.TURTLE);

        Resource root = findRootResource(model);
        if (root == null) {
            throw new IllegalStateException("Unable to locate FAIR Data Point root resource");
        }

        Property titleProperty = model.createProperty(DCT + "title");
        Property descriptionProperty = model.createProperty(DCT + "description");
        Property publisherProperty = model.createProperty(DCT + "publisher");
        Property nameProperty = model.createProperty(FOAF + "name");

        pruneGeneratedRootStatements(model, root);
        model.removeAll(root, titleProperty, null);
        model.removeAll(root, descriptionProperty, null);
        model.removeAll(root, publisherProperty, null);

        root.addProperty(titleProperty, nodeName + " FAIR Data Point");
        root.addProperty(descriptionProperty, nodeDescription);

        Resource publisher = null;
        StmtIterator publisherStatements = root.listProperties(publisherProperty);
        if (publisherStatements.hasNext()) {
            Resource existingPublisher = publisherStatements.nextStatement().getObject().asResource();
            publisher = existingPublisher;
        }
        if (publisher == null) {
            publisher = model.createResource(root.getURI() + "#publisher");
        }
        publisher.removeProperties();
        publisher.addProperty(RDF.type, model.createResource(FOAF + "Agent"));
        publisher.addProperty(nameProperty, nodeName);
        root.addProperty(publisherProperty, publisher);

        StringWriter writer = new StringWriter();
        model.write(writer, "TTL");
        return writer.toString();
    }

    private void pruneGeneratedRootStatements(Model model, Resource root) {
        model.removeAll(root, model.createProperty(DCT + "conformsTo"), null);
        model.removeAll(root, model.createProperty(DCAT + "endpointURL"), null);
        model.removeAll(root, model.createProperty(FDP_O + "fdpSoftwareVersion"), null);
        model.removeAll(root, model.createProperty(SIO + "SIO_000628"), null);
        model.removeAll(root, model.createProperty(RDFS + "label"), null);
    }

    private Resource findRootResource(Model model) {
        Resource root = firstResourceWithType(model, DCAT + "DataService");
        if (root != null) {
            return root;
        }
        root = firstResourceWithType(model, FDP_O + "MetadataService");
        if (root != null) {
            return root;
        }
        return firstResourceWithType(model, FDP_O + "FAIRDataPoint");
    }

    private Resource firstResourceWithType(Model model, String typeUri) {
        List<Resource> resources = model.listResourcesWithProperty(RDF.type, model.createResource(typeUri)).toList();
        return resources.isEmpty() ? null : resources.get(0);
    }

    private HttpHeaders acceptTurtleHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(TURTLE));
        return headers;
    }

    private HttpHeaders brandHeaders(String bearerToken) {
        HttpHeaders headers = acceptTurtleHeaders();
        headers.setContentType(TURTLE);
        headers.setBearerAuth(bearerToken);
        return headers;
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
