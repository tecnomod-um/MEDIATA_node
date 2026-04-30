package org.taniwha.util;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import org.taniwha.model.MetadataDocument;
import org.taniwha.service.FileService;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class FairDataPointMetadataUtil {

    public static final String MANAGED_METADATA_FILE_NAME = "fairdatapoint-generated.ttl";

    private static final String DCAT = "http://www.w3.org/ns/dcat#";
    private static final String DCT = "http://purl.org/dc/terms/";
    private static final String FOAF = "http://xmlns.com/foaf/0.1/";
    private static final String HEALTH_THEME = "http://publications.europa.eu/resource/authority/data-theme/HEAL";
    private static final String RIGHTS = "http://purl.org/dc/terms/RightsStatement";
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");

    private final FileService fileService;
    private final String nodeName;
    private final String nodeDescription;
    private final String nodeIp;
    private final String serverPort;
    private final String contextPath;

    public FairDataPointMetadataUtil(FileService fileService,
                                     @Value("${name:unnamed}") String nodeName,
                                     @Value("${desc:no description available}") String nodeDescription,
                                     @Value("${node.ip:}") String nodeIp,
                                     @Value("${server.port:8080}") String serverPort,
                                     @Value("${server.servlet.context-path:/taniwha}") String contextPath) {
        this.fileService = fileService;
        this.nodeName = isBlank(nodeName) ? "unnamed" : nodeName.trim();
        this.nodeDescription = isBlank(nodeDescription) ? "no description available" : nodeDescription.trim();
        this.nodeIp = nodeIp == null ? "" : nodeIp.trim();
        this.serverPort = isBlank(serverPort) ? "8080" : serverPort.trim();
        this.contextPath = normalizeContextPath(contextPath);
    }

    public MetadataDocument generateManagedMetadataDocument() {
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("dcat", DCAT);
        model.setNsPrefix("dct", DCT);
        model.setNsPrefix("foaf", FOAF);

        String nodeBaseUrl = resolveNodeBaseUrl();
        Resource catalog = model.createResource(nodeBaseUrl + "/fdp/catalog/node-catalog");
        Property datasetProperty = model.createProperty(DCAT + "dataset");
        Property distributionProperty = model.createProperty(DCAT + "distribution");
        Property themeProperty = model.createProperty(DCAT + "theme");
        Property accessUrlProperty = model.createProperty(DCAT + "accessURL");
        Property mediaTypeProperty = model.createProperty(DCAT + "mediaType");
        Property identifierProperty = model.createProperty(DCT + "identifier");
        Property isPartOfProperty = model.createProperty(DCT + "isPartOf");
        Property accessRightsProperty = model.createProperty(DCT + "accessRights");

        addCommonResourceFields(
                model,
                catalog,
                "FAIR metadata catalog",
                nodeName + " FAIR metadata catalog",
                nodeDescription
        );
        catalog.addProperty(RDF.type, model.createResource(DCAT + "Catalog"));

        List<String> datasetFiles = new ArrayList<>(fileService.listDatasetFiles());
        datasetFiles.sort(String.CASE_INSENSITIVE_ORDER);

        for (String datasetFile : datasetFiles) {
            String stem = stripExtension(datasetFile);
            String slug = slugify(stem);
            String datasetTitle = humanize(stem);

            Resource dataset = model.createResource(nodeBaseUrl + "/fdp/dataset/" + slug);
            addCommonResourceFields(
                    model,
                    dataset,
                    stem,
                    datasetTitle,
                    "Dataset file exposed by the " + nodeName + " node."
            );
            dataset.addProperty(RDF.type, model.createResource(DCAT + "Dataset"));
            dataset.addProperty(identifierProperty, stem);
            dataset.addProperty(themeProperty, model.createResource(HEALTH_THEME));
            dataset.addProperty(isPartOfProperty, catalog);
            dataset.addProperty(accessRightsProperty, restrictedAccessRights(model, nodeBaseUrl));
            catalog.addProperty(datasetProperty, dataset);

            Resource distribution = model.createResource(nodeBaseUrl + "/fdp/distribution/" + slug);
            addCommonResourceFields(
                    model,
                    distribution,
                    stem + "-distribution",
                    datasetTitle + " distribution",
                    "Access instructions for dataset file " + datasetFile + ". Raw file access requires node authorization."
            );
            distribution.addProperty(RDF.type, model.createResource(DCAT + "Distribution"));
            distribution.addProperty(isPartOfProperty, dataset);
            distribution.addProperty(accessRightsProperty, restrictedAccessRights(model, nodeBaseUrl));

            String accessUrl = nodeBaseUrl + "/fdp/access/" + UriUtils.encodePathSegment(slug, StandardCharsets.UTF_8);
            distribution.addProperty(accessUrlProperty, model.createResource(accessUrl));
            distribution.addProperty(mediaTypeProperty, resolveMediaTypeValue(datasetFile));

            dataset.addProperty(distributionProperty, distribution);
        }

        StringWriter writer = new StringWriter();
        model.write(writer, "TTL");
        return fileService.writeMetadataDocument(MANAGED_METADATA_FILE_NAME, writer.toString());
    }

    private void addCommonResourceFields(Model model,
                                         Resource resource,
                                         String identifier,
                                         String title,
                                         String description) {
        Property titleProperty = model.createProperty(DCT + "title");
        Property descriptionProperty = model.createProperty(DCT + "description");
        Property publisherProperty = model.createProperty(DCT + "publisher");
        Property versionProperty = model.createProperty(DCT + "hasVersion");
        Property identifierProperty = model.createProperty(DCT + "identifier");
        Property foafNameProperty = model.createProperty(FOAF + "name");

        resource.addProperty(RDF.type, model.createResource(DCAT + "Resource"));
        resource.addProperty(titleProperty, title);
        resource.addProperty(descriptionProperty, description);
        resource.addProperty(versionProperty, "1.0");
        resource.addProperty(identifierProperty, identifier);

        Resource publisher = model.createResource();
        publisher.addProperty(RDF.type, model.createResource(FOAF + "Agent"));
        publisher.addProperty(foafNameProperty, nodeName);
        resource.addProperty(publisherProperty, publisher);
    }

    private Resource restrictedAccessRights(Model model, String nodeBaseUrl) {
        Resource rights = model.createResource(trimTrailingSlash(nodeBaseUrl) + "#authorized-node-access");
        rights.addProperty(RDF.type, model.createResource(RIGHTS));
        rights.addProperty(model.createProperty(DCT + "description"),
                "Access to the described data requires node authorization.");
        return rights;
    }

    private String resolveNodeBaseUrl() {
        String resolvedBase;

        if (isBlank(nodeIp)) {
            resolvedBase = "http://localhost:" + serverPort;
        } else if (nodeIp.startsWith("http://") || nodeIp.startsWith("https://")) {
            resolvedBase = nodeIp;
        } else if (nodeIp.matches("^\\d+$")) {
            resolvedBase = "http://localhost:" + nodeIp;
        } else {
            resolvedBase = "http://" + nodeIp;
        }

        return trimTrailingSlash(resolvedBase) + contextPath;
    }

    private String normalizeContextPath(String rawContextPath) {
        String normalized = isBlank(rawContextPath) ? "" : rawContextPath.trim();
        if (normalized.isEmpty() || "/".equals(normalized)) {
            return "";
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return trimTrailingSlash(normalized);
    }

    private String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String resolveMediaTypeValue(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            return "text/csv";
        }
        if (lower.endsWith(".tsv")) {
            return "text/tab-separated-values";
        }
        if (lower.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        return "application/octet-stream";
    }

    private String stripExtension(String fileName) {
        if (fileName == null) {
            return "dataset";
        }
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }

    private String slugify(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        normalized = NON_SLUG.matcher(normalized).replaceAll("-");
        normalized = normalized.replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "dataset" : normalized;
    }

    private String humanize(String value) {
        if (isBlank(value)) {
            return "Dataset";
        }

        String[] parts = value.replace('_', ' ').replace('-', ' ').trim().split("\\s+");
        List<String> titledParts = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            titledParts.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1));
        }
        return titledParts.isEmpty() ? "Dataset" : String.join(" ", titledParts);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
