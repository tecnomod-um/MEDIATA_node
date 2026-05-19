package org.taniwha.service;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.taniwha.config.RestTemplateHolder;
import org.taniwha.model.FairDataPointSyncResult;
import org.taniwha.model.FairDataPointPublishedManifest;
import org.taniwha.model.MetadataDocument;
import org.taniwha.util.DCatUtil;
import org.taniwha.util.FairDataPointMetadataUtil;

import java.io.StringWriter;
import java.net.URI;
import java.util.*;

@Service
public class FairDataPointCatalogSyncService {

    private static final Logger logger = LoggerFactory.getLogger(FairDataPointCatalogSyncService.class);
    private static final String DCAT = "http://www.w3.org/ns/dcat#";
    private static final String DCT = "http://purl.org/dc/terms/";
    private static final String FOAF = "http://xmlns.com/foaf/0.1/";
    private static final String XSD_DATE = "http://www.w3.org/2001/XMLSchema#date";
    private static final String XSD_DATE_TIME = "http://www.w3.org/2001/XMLSchema#dateTime";
    private static final MediaType TURTLE = MediaType.parseMediaType("text/turtle");
    private static final String HEALTH_THEME = "http://publications.europa.eu/resource/authority/data-theme/HEAL";
    private static final String DEFAULT_MEDIA_TYPE = "application/octet-stream";
    private static final String DEFAULT_FDP_EMAIL = "albert.einstein@example.com";
    private static final String DEFAULT_FDP_PASSWORD = "password";

    private final FileService fileService;
    private final FairDataPointMetadataUtil fairDataPointMetadataUtil;
    private final RestTemplateHolder restTemplateHolder;
    private final RetryTemplate retryTemplate;
    private final boolean enabled;
    private final String baseUrl;
    private final String persistentUrl;
    private final String email;
    private final String password;
    private final String apiKey;
    private final String nodeName;

    public FairDataPointCatalogSyncService(FileService fileService,
                                           FairDataPointMetadataUtil fairDataPointMetadataUtil,
                                           RestTemplateHolder restTemplateHolder,
                                           RetryTemplate retryTemplate,
                                           @Value("${fairdatapoint.enabled:false}") boolean enabled,
                                           @Value("${fairdatapoint.base-url:}") String baseUrl,
                                           @Value("${fairdatapoint.persistent-url:}") String persistentUrl,
                                           @Value("${fairdatapoint.email:}") String email,
                                           @Value("${fairdatapoint.password:}") String password,
                                           @Value("${fairdatapoint.api-key:}") String apiKey,
                                           @Value("${name:unnamed}") String nodeName) {
        this.fileService = fileService;
        this.fairDataPointMetadataUtil = fairDataPointMetadataUtil;
        this.restTemplateHolder = restTemplateHolder;
        this.retryTemplate = retryTemplate;
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.persistentUrl = persistentUrl == null ? "" : persistentUrl.trim();
        this.email = email == null ? "" : email.trim();
        this.password = password == null ? "" : password;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.nodeName = nodeName == null || nodeName.isBlank() ? "unnamed" : nodeName.trim();
    }

    public FairDataPointSyncResult publishCatalogs() {
        ensureEnabled();
        fairDataPointMetadataUtil.generateManagedMetadataDocument();

        List<MetadataDocument> documents = fileService.readAllMetadataDocuments();
        if (documents.isEmpty()) {
            fileService.deleteFairDataPointPublishedManifest();
            return new FairDataPointSyncResult(
                    "NO_METADATA",
                    0,
                    0,
                    0,
                    0,
                    List.of(),
                    List.of(),
                    List.of(),
                    "No metadata files were found in dataset_metadata."
            );
        }

        String bearerToken = resolveBearerToken();
        assertMetadataServiceRootAvailable(bearerToken);
        resetPublishedMetadataState(bearerToken);
        assertMetadataServiceRootAvailable(bearerToken);
        List<String> createdCatalogUris = new ArrayList<>();
        List<String> createdDatasetUris = new ArrayList<>();
        List<String> createdDistributionUris = new ArrayList<>();

        try {
            for (MetadataDocument document : documents) {
                Model model = DCatUtil.readModel(document.content(), document.fileName());
                publishDocumentGraph(
                        bearerToken,
                        model,
                        document.fileName(),
                        createdCatalogUris,
                        createdDatasetUris,
                        createdDistributionUris
                );
            }
            fileService.writeFairDataPointPublishedManifest(new FairDataPointPublishedManifest(
                    List.copyOf(createdCatalogUris),
                    List.copyOf(createdDatasetUris),
                    List.copyOf(createdDistributionUris)
            ));
        } catch (RuntimeException e) {
            cleanupPartialPublishedState(bearerToken, e);
            throw e;
        }

        return new FairDataPointSyncResult(
                "COMPLETED",
                documents.size(),
                createdCatalogUris.size(),
                createdDatasetUris.size(),
                createdDistributionUris.size(),
                createdCatalogUris,
                createdDatasetUris,
                createdDistributionUris,
                createdCatalogUris.isEmpty()
                        ? "Metadata files were read, but no dcat:Catalog resources were found."
                        : "Catalog, dataset, and distribution metadata was published to the FAIR Data Point instance."
        );
    }

    private void publishDocumentGraph(String bearerToken,
                                      Model model,
                                      String fileName,
                                      List<String> createdCatalogUris,
                                      List<String> createdDatasetUris,
                                      List<String> createdDistributionUris) {
        List<Resource> catalogs = listResourcesWithType(model, DCAT + "Catalog");
        List<Resource> datasets = listResourcesWithType(model, DCAT + "Dataset");
        List<Resource> distributions = listResourcesWithType(model, DCAT + "Distribution");

        if (catalogs.isEmpty()) {
            logger.debug("Skipping metadata file without dcat:Catalog resources: {}", fileName);
            return;
        }

        Map<String, URI> createdCatalogBySource = new LinkedHashMap<>();
        int catalogIndex = 1;
        for (Resource catalog : catalogs) {
            URI createdCatalog = createMetadataResource(
                    "catalog",
                    bearerToken,
                    toTurtle(prepareCatalogModel(model, catalog, fileName, catalogIndex++))
            );
            publishMetadataResource(bearerToken, createdCatalog);
            createdCatalogBySource.put(resourceKey(catalog), createdCatalog);
            createdCatalogUris.add(createdCatalog.toString());
            logger.info("Published catalog from metadata file {} to FAIR Data Point resource {}",
                    fileName, createdCatalog);
        }

        Map<String, URI> createdDatasetBySource = new LinkedHashMap<>();
        int datasetIndex = 1;
        for (Resource dataset : datasets) {
            URI parentCatalogUri = resolveParentResourceUri(
                    model,
                    dataset,
                    DCT + "isPartOf",
                    DCAT + "dataset",
                    createdCatalogBySource
            );

            if (parentCatalogUri == null) {
                logger.warn("Skipping dataset without resolvable parent catalog in metadata file {}", fileName);
                continue;
            }

            URI createdDataset = createMetadataResource(
                    "dataset",
                    bearerToken,
                    toTurtle(prepareDatasetModel(model, dataset, fileName, datasetIndex++, parentCatalogUri))
            );
            publishMetadataResource(bearerToken, createdDataset);
            createdDatasetBySource.put(resourceKey(dataset), createdDataset);
            createdDatasetUris.add(createdDataset.toString());
        }

        int distributionIndex = 1;
        for (Resource distribution : distributions) {
            URI parentDatasetUri = resolveParentResourceUri(
                    model,
                    distribution,
                    DCT + "isPartOf",
                    DCAT + "distribution",
                    createdDatasetBySource
            );

            if (parentDatasetUri == null) {
                logger.warn("Skipping distribution without resolvable parent dataset in metadata file {}", fileName);
                continue;
            }

            URI createdDistribution = createMetadataResource(
                    "distribution",
                    bearerToken,
                    toTurtle(prepareDistributionModel(model, distribution, fileName, distributionIndex++, parentDatasetUri))
            );
            publishMetadataResource(bearerToken, createdDistribution);
            createdDistributionUris.add(createdDistribution.toString());
        }
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new IllegalStateException("FAIR Data Point integration is disabled");
        }
        if (isBlank(baseUrl)) {
            throw new IllegalStateException("FAIR Data Point base URL is not configured");
        }
    }

    private void assertMetadataServiceRootAvailable(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        headers.setAccept(List.of(TURTLE));

        try {
            retryTemplate.execute((RetryCallback<ResponseEntity<String>, RestClientException>) context -> {
                if (context.getRetryCount() > 0) {
                    logger.warn("Retrying FAIR Data Point root metadata check. Attempt {}", context.getRetryCount() + 1);
                }
                return restTemplateHolder.get().exchange(
                        normalizeBaseUrl(baseUrl) + "/",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class
                );
            });
        } catch (HttpClientErrorException.NotFound notFound) {
            throw new IllegalStateException(
                    "FAIR Data Point root metadata is not initialized. Initialize the MetadataService root before syncing catalogs."
            );
        }
    }

    private void resetPublishedMetadataState(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        Map<String, Boolean> body = Map.of(
                "metadata", true,
                "users", false,
                "resourceDefinitions", false,
                "settings", false
        );

        retryTemplate.execute((RetryCallback<ResponseEntity<Void>, RestClientException>) context -> {
            if (context.getRetryCount() > 0) {
                logger.warn("Retrying FAIR Data Point metadata reset. Attempt {}", context.getRetryCount() + 1);
            }
            return restTemplateHolder.get().exchange(
                    normalizeBaseUrl(baseUrl) + "/reset",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Void.class
            );
        });
    }

    private void cleanupPartialPublishedState(String bearerToken, RuntimeException originalError) {
        try {
            resetPublishedMetadataState(bearerToken);
            fileService.deleteFairDataPointPublishedManifest();
            assertMetadataServiceRootAvailable(bearerToken);
            logger.warn("Cleaned partially published FAIR Data Point state after sync failure.");
        } catch (RuntimeException cleanupError) {
            logger.error("Failed to clean FAIR Data Point state after sync failure", cleanupError);
            originalError.addSuppressed(cleanupError);
        }
    }

    private String resolveBearerToken() {
        if (!isBlank(apiKey)) {
            return apiKey.trim();
        }

        String username = isBlank(email) ? DEFAULT_FDP_EMAIL : email.trim();
        String secret = isBlank(password) ? DEFAULT_FDP_PASSWORD : password;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        Map<String, String> body = Map.of(
                "email", username,
                "password", secret
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> response = retryTemplate.execute((RetryCallback<Map<String, Object>, RestClientException>) context -> {
            if (context.getRetryCount() > 0) {
                logger.warn("Retrying FAIR Data Point token request. Attempt {}", context.getRetryCount() + 1);
            }
            RestTemplate restTemplate = restTemplateHolder.get();
            return restTemplate.postForObject(
                    normalizeBaseUrl(baseUrl) + "/tokens",
                    new HttpEntity<>(body, headers),
                    Map.class
            );
        });

        if (response == null || !(response.get("token") instanceof String token) || token.isBlank()) {
            throw new IllegalStateException("FAIR Data Point token endpoint did not return a valid token");
        }

        return token;
    }

    private URI createMetadataResource(String pathSegment, String bearerToken, String turtlePayload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        headers.setContentType(TURTLE);
        headers.setAccept(List.of(TURTLE));

        ResponseEntity<String> response = retryTemplate.execute((RetryCallback<ResponseEntity<String>, RestClientException>) context -> {
            if (context.getRetryCount() > 0) {
                logger.warn("Retrying FAIR Data Point {} creation. Attempt {}", pathSegment, context.getRetryCount() + 1);
            }
            return restTemplateHolder.get().exchange(
                    normalizeBaseUrl(baseUrl) + "/" + pathSegment,
                    HttpMethod.POST,
                    new HttpEntity<>(turtlePayload, headers),
                    String.class
            );
        });

        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("FAIR Data Point catalog creation did not return a Location header");
        }

        return location;
    }

    private Model prepareCatalogModel(Model source, Resource originalCatalog, String fileName) {
        Model result = ModelFactory.createDefaultModel();
        result.setNsPrefixes(source.getNsPrefixMap());

        Resource targetCatalog = createPostTargetResource(result);
        copyCatalogStatements(source, originalCatalog, targetCatalog, result, new HashSet<>());
        removeDatasetLinks(targetCatalog, result);
        ensureCatalogMinimumFields(source, originalCatalog, targetCatalog, result, fileName);

        return result;
    }

    private Model prepareDatasetModel(Model source,
                                      Resource originalDataset,
                                      String fileName,
                                      URI parentCatalogUri) {
        Model result = ModelFactory.createDefaultModel();
        result.setNsPrefixes(source.getNsPrefixMap());

        Resource targetDataset = createPostTargetResource(result);
        copyCatalogStatements(source, originalDataset, targetDataset, result, new HashSet<>());
        removeChildLinks(targetDataset, result, DCAT + "distribution");
        ensureDatasetMinimumFields(source, originalDataset, targetDataset, result, fileName, parentCatalogUri);

        return result;
    }

    private Model prepareDistributionModel(Model source,
                                           Resource originalDistribution,
                                           String fileName,
                                           URI parentDatasetUri) {
        Model result = ModelFactory.createDefaultModel();
        result.setNsPrefixes(source.getNsPrefixMap());

        Resource targetDistribution = createPostTargetResource(result);
        copyCatalogStatements(source, originalDistribution, targetDistribution, result, new HashSet<>());
        ensureDistributionMinimumFields(source, originalDistribution, targetDistribution, result, fileName, parentDatasetUri);

        return result;
    }

    private Resource createPostTargetResource(Model model) {
        // FDP expects create payloads to describe the resource being created as the request target ("<>").
        return model.createResource("");
    }

    private void copyCatalogStatements(Model source,
                                       Resource originalCatalog,
                                       Resource targetCatalog,
                                       Model targetModel,
                                       Set<Resource> visitedBlankNodes) {
        StmtIterator iterator = source.listStatements(originalCatalog, null, (RDFNode) null);
        while (iterator.hasNext()) {
            Statement statement = iterator.nextStatement();
            RDFNode object = statement.getObject();
            targetModel.add(targetCatalog, statement.getPredicate(), object);

            if (object.isResource() && object.asResource().isAnon()) {
                copyBlankNodeClosure(source, object.asResource(), targetModel, visitedBlankNodes);
            }
        }
    }

    private void removeDatasetLinks(Resource catalog, Model model) {
        removeChildLinks(catalog, model, DCAT + "dataset");
    }

    private void removeChildLinks(Resource resource, Model model, String propertyUri) {
        Property property = model.createProperty(propertyUri);
        model.removeAll(resource, property, null);
    }

    private void ensureCatalogMinimumFields(Model source,
                                            Resource originalCatalog,
                                            Resource catalog,
                                            Model model,
                                            String fileName) {
        Property titleProperty = model.createProperty(DCT + "title");
        Property publisherProperty = model.createProperty(DCT + "publisher");
        Property versionProperty = model.createProperty(DCT + "hasVersion");
        Property isPartOfProperty = model.createProperty(DCT + "isPartOf");
        Property dcatVersionProperty = model.createProperty(DCAT + "version");
        Property foafNameProperty = model.createProperty(FOAF + "name");

        if (!model.contains(catalog, titleProperty)) {
            model.add(catalog, titleProperty, stripExtension(fileName));
        }

        if (!model.contains(catalog, versionProperty)) {
            String existingVersion = firstString(catalog, dcatVersionProperty.getURI());
            model.add(catalog, versionProperty, isBlank(existingVersion) ? "1.0" : existingVersion);
        }

        model.removeAll(catalog, isPartOfProperty, null);
        model.add(catalog, isPartOfProperty, model.createResource(resolvePersistentUrl()));

        normalizeDateTimeLiteral(catalog, model, model.createProperty(DCT + "issued"));
        normalizeDateTimeLiteral(catalog, model, model.createProperty(DCT + "modified"));
        ensureAgentResource(source, originalCatalog, catalog, model, publisherProperty, foafNameProperty);
        keepSingleResourceValue(catalog, model, publisherProperty);
    }

    private void ensureDatasetMinimumFields(Model source,
                                            Resource originalDataset,
                                            Resource dataset,
                                            Model model,
                                            String fileName,
                                            URI parentCatalogUri) {
        Property titleProperty = model.createProperty(DCT + "title");
        Property publisherProperty = model.createProperty(DCT + "publisher");
        Property creatorProperty = model.createProperty(DCT + "creator");
        Property versionProperty = model.createProperty(DCT + "hasVersion");
        Property isPartOfProperty = model.createProperty(DCT + "isPartOf");
        Property dcatVersionProperty = model.createProperty(DCAT + "version");
        Property themeProperty = model.createProperty(DCAT + "theme");
        Property contactPointProperty = model.createProperty(DCAT + "contactPoint");
        Property foafNameProperty = model.createProperty(FOAF + "name");

        if (!model.contains(dataset, titleProperty)) {
            model.add(dataset, titleProperty, stripExtension(fileName));
        }

        if (!model.contains(dataset, versionProperty)) {
            String existingVersion = firstString(dataset, dcatVersionProperty.getURI());
            model.add(dataset, versionProperty, isBlank(existingVersion) ? "1.0" : existingVersion);
        }

        model.removeAll(dataset, isPartOfProperty, null);
        model.add(dataset, isPartOfProperty, model.createResource(parentCatalogUri.toString()));

        if (!model.contains(dataset, themeProperty)) {
            model.add(dataset, themeProperty, model.createResource(HEALTH_THEME));
        }

        normalizeDateTimeLiteral(dataset, model, model.createProperty(DCT + "issued"));
        normalizeDateTimeLiteral(dataset, model, model.createProperty(DCT + "modified"));
        ensureAgentResource(source, originalDataset, dataset, model, publisherProperty, foafNameProperty);
        ensureAgentResource(source, originalDataset, dataset, model, creatorProperty, foafNameProperty);
        materializeReferencedResources(source, originalDataset, model, contactPointProperty);
        keepSingleResourceValue(dataset, model, publisherProperty);
        keepSingleResourceValue(dataset, model, creatorProperty);
        keepSingleResourceValue(dataset, model, contactPointProperty);
    }

    private void ensureDistributionMinimumFields(Model source,
                                                 Resource originalDistribution,
                                                 Resource distribution,
                                                 Model model,
                                                 String fileName,
                                                 URI parentDatasetUri) {
        Property titleProperty = model.createProperty(DCT + "title");
        Property publisherProperty = model.createProperty(DCT + "publisher");
        Property versionProperty = model.createProperty(DCT + "hasVersion");
        Property isPartOfProperty = model.createProperty(DCT + "isPartOf");
        Property dcatVersionProperty = model.createProperty(DCAT + "version");
        Property mediaTypeProperty = model.createProperty(DCAT + "mediaType");
        Property contactPointProperty = model.createProperty(DCAT + "contactPoint");
        Property foafNameProperty = model.createProperty(FOAF + "name");

        if (!model.contains(distribution, titleProperty)) {
            model.add(distribution, titleProperty, stripExtension(fileName));
        }

        if (!model.contains(distribution, versionProperty)) {
            String existingVersion = firstString(distribution, dcatVersionProperty.getURI());
            model.add(distribution, versionProperty, isBlank(existingVersion) ? "1.0" : existingVersion);
        }

        model.removeAll(distribution, isPartOfProperty, null);
        model.add(distribution, isPartOfProperty, model.createResource(parentDatasetUri.toString()));

        if (!model.contains(distribution, mediaTypeProperty)) {
            model.add(distribution, mediaTypeProperty, DEFAULT_MEDIA_TYPE);
        } else {
            List<RDFNode> mediaTypes = model.listObjectsOfProperty(distribution, mediaTypeProperty).toList();
            for (RDFNode mediaType : mediaTypes) {
                if (mediaType.isResource()) {
                    model.removeAll(distribution, mediaTypeProperty, mediaType);
                    model.add(distribution, mediaTypeProperty, mediaType.asResource().getURI());
                }
            }
        }

        normalizeDateTimeLiteral(distribution, model, model.createProperty(DCT + "issued"));
        normalizeDateTimeLiteral(distribution, model, model.createProperty(DCT + "modified"));
        ensureAgentResource(source, originalDistribution, distribution, model, publisherProperty, foafNameProperty);
        materializeReferencedResources(source, originalDistribution, model, contactPointProperty);
        keepSingleResourceValue(distribution, model, publisherProperty);
        keepSingleResourceValue(distribution, model, contactPointProperty);
    }

    private void publishMetadataResource(String bearerToken, URI catalogUri) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        Map<String, String> body = Map.of("current", "PUBLISHED");

        retryTemplate.execute((RetryCallback<ResponseEntity<Void>, RestClientException>) context -> {
            if (context.getRetryCount() > 0) {
                logger.warn("Retrying FAIR Data Point catalog publish state change. Attempt {}", context.getRetryCount() + 1);
            }
            return restTemplateHolder.get().exchange(
                    resolveManageableResourceUri(catalogUri) + "/meta/state",
                    HttpMethod.PUT,
                    new HttpEntity<>(body, headers),
                    Void.class
            );
        });
    }

    private List<Resource> listResourcesWithType(Model model, String typeUri) {
        return model.listResourcesWithProperty(RDF.type, model.createResource(typeUri)).toList();
    }

    private URI resolveParentResourceUri(Model model,
                                         Resource childResource,
                                         String isPartOfPropertyUri,
                                         String inverseRelationUri,
                                         Map<String, URI> createdParents) {
        Resource parent = firstResource(childResource, isPartOfPropertyUri);
        if (parent != null && createdParents.containsKey(resourceKey(parent))) {
            return createdParents.get(resourceKey(parent));
        }

        ResIterator inverseParents = model.listResourcesWithProperty(model.createProperty(inverseRelationUri), childResource);
        while (inverseParents.hasNext()) {
            Resource inverseParent = inverseParents.nextResource();
            URI createdParent = createdParents.get(resourceKey(inverseParent));
            if (createdParent != null) {
                return createdParent;
            }
        }

        if (createdParents.size() == 1) {
            return createdParents.values().iterator().next();
        }

        return null;
    }

    private Resource firstResource(Resource resource, String propertyUri) {
        Statement statement = resource.getProperty(resource.getModel().createProperty(propertyUri));
        if (statement == null || !statement.getObject().isResource()) {
            return null;
        }
        return statement.getObject().asResource();
    }

    private String resourceKey(Resource resource) {
        if (resource.isURIResource()) {
            return resource.getURI();
        }
        return "_:" + resource.getId().getLabelString();
    }

    private void copyBlankNodeClosure(Model source,
                                      Resource resource,
                                      Model target,
                                      Set<Resource> visited) {
        if (!visited.add(resource)) {
            return;
        }

        StmtIterator iterator = source.listStatements(resource, null, (RDFNode) null);
        while (iterator.hasNext()) {
            Statement statement = iterator.nextStatement();
            target.add(statement);

            RDFNode object = statement.getObject();
            if (object.isResource() && object.asResource().isAnon()) {
                copyBlankNodeClosure(source, object.asResource(), target, visited);
            }
        }
    }

    private void materializeReferencedResources(Model source,
                                                Resource originalSubject,
                                                Model targetModel,
                                                Property property) {
        StmtIterator statements = originalSubject.listProperties(source.createProperty(property.getURI()));
        while (statements.hasNext()) {
            Statement statement = statements.nextStatement();
            if (!statement.getObject().isResource()) {
                continue;
            }
            copyNamedResourceClosure(source, statement.getObject().asResource(), targetModel, new HashSet<>(), new HashSet<>());
        }
    }

    private void ensureAgentResource(Model source,
                                     Resource originalSubject,
                                     Resource targetSubject,
                                     Model targetModel,
                                     Property agentProperty,
                                     Property nameProperty) {
        materializeReferencedResources(source, originalSubject, targetModel, agentProperty);

        if (!targetModel.contains(targetSubject, agentProperty)) {
            Resource publisher = targetModel.createResource();
            publisher.addProperty(RDF.type, targetModel.createResource(FOAF + "Agent"));
            publisher.addProperty(nameProperty, nodeName);
            targetModel.add(targetSubject, agentProperty, publisher);
            return;
        }

        List<RDFNode> agents = targetModel.listObjectsOfProperty(targetSubject, agentProperty).toList();
        for (RDFNode agent : agents) {
            if (!agent.isResource()) {
                continue;
            }
            Resource resource = agent.asResource();
            if (!targetModel.contains(resource, nameProperty)) {
                resource.addProperty(RDF.type, targetModel.createResource(FOAF + "Agent"));
                resource.addProperty(nameProperty, nodeName);
            }
        }
    }

    private void keepSingleResourceValue(Resource subject, Model model, Property property) {
        List<Statement> statements = model.listStatements(subject, property, (RDFNode) null).toList();
        if (statements.size() <= 1) {
            return;
        }

        Statement preferred = statements.stream()
                .filter(statement -> statement.getObject().isResource())
                .filter(statement -> statement.getObject().asResource().hasProperty(model.createProperty(FOAF + "name"))
                        || statement.getObject().asResource().hasProperty(RDF.type))
                .findFirst()
                .orElse(statements.get(0));

        for (Statement statement : statements) {
            if (!statement.equals(preferred)) {
                model.remove(statement);
            }
        }
    }

    private void copyNamedResourceClosure(Model source,
                                          Resource resource,
                                          Model target,
                                          Set<String> visitedResources,
                                          Set<Resource> visitedBlankNodes) {
        String resourceKey = resourceKey(resource);
        if (!visitedResources.add(resourceKey)) {
            return;
        }

        StmtIterator iterator = source.listStatements(resource, null, (RDFNode) null);
        while (iterator.hasNext()) {
            Statement statement = iterator.nextStatement();
            target.add(statement);

            RDFNode object = statement.getObject();
            if (object.isResource() && object.asResource().isAnon()) {
                copyBlankNodeClosure(source, object.asResource(), target, visitedBlankNodes);
            }
        }
    }

    private void normalizeDateTimeLiteral(Resource resource, Model model, Property property) {
        List<Statement> statements = model.listStatements(resource, property, (RDFNode) null).toList();
        for (Statement statement : statements) {
            if (!statement.getObject().isLiteral()) {
                continue;
            }
            String lexicalForm = statement.getLiteral().getLexicalForm();
            String datatypeUri = statement.getLiteral().getDatatypeURI();
            if (XSD_DATE.equals(datatypeUri)) {
                model.remove(statement);
                model.add(resource, property, model.createTypedLiteral(lexicalForm + "T00:00:00Z", XSD_DATE_TIME));
                continue;
            }
            if (datatypeUri == null && lexicalForm.matches("\\d{4}-\\d{2}-\\d{2}")) {
                model.remove(statement);
                model.add(resource, property, model.createTypedLiteral(lexicalForm + "T00:00:00Z", XSD_DATE_TIME));
            }
        }
    }

    private String toTurtle(Model model) {
        StringWriter writer = new StringWriter();
        model.write(writer, "TTL");
        return writer.toString();
    }

    private URI resolveManageableResourceUri(URI resourceUri) {
        if (resourceUri == null) {
            throw new IllegalArgumentException("FAIR Data Point resource URI cannot be null");
        }

        String base = normalizeBaseUrl(baseUrl);
        String persistent = resolvePersistentUrl();

        if (isBlank(base) || isBlank(persistent) || persistent.equals(base)) {
            return resourceUri;
        }

        String rawResourceUri = resourceUri.toString();
        if (!rawResourceUri.startsWith(persistent)) {
            return resourceUri;
        }

        return URI.create(base + rawResourceUri.substring(persistent.length()));
    }

    private String resolvePersistentUrl() {
        if (!isBlank(persistentUrl)) {
            return normalizeBaseUrl(persistentUrl);
        }
        return normalizeBaseUrl(baseUrl);
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String firstString(Resource resource, String propertyUri) {
        Statement statement = resource.getProperty(resource.getModel().createProperty(propertyUri));
        if (statement == null || !statement.getObject().isLiteral()) {
            return null;
        }
        return statement.getObject().asLiteral().getString();
    }

    private String stripExtension(String fileName) {
        if (fileName == null) {
            return "catalog";
        }
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
