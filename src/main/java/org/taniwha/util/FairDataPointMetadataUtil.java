package org.taniwha.util;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.vocabulary.RDF;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import org.taniwha.model.MetadataDocument;
import org.taniwha.service.FileService;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class FairDataPointMetadataUtil {

    public static final String MANAGED_METADATA_FILE_NAME = "fairdatapoint-generated.ttl";

    private static final String DCAT = "http://www.w3.org/ns/dcat#";
    private static final String DCT = "http://purl.org/dc/terms/";
    private static final String FOAF = "http://xmlns.com/foaf/0.1/";
    private static final String VCARD = "http://www.w3.org/2006/vcard/ns#";
    private static final String HEALTH_THEME = "http://publications.europa.eu/resource/authority/data-theme/HEAL";
    private static final String RIGHTS = "http://purl.org/dc/terms/RightsStatement";
    private static final String DEFAULT_LICENSE = "http://purl.org/NET/rdflicense/allrightsreserved";
    private static final String DEFAULT_LANGUAGE = "http://publications.europa.eu/resource/authority/language/ENG";
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");

    private final FileService fileService;
    private final String nodeName;
    private final String nodeDescription;
    private final String nodeIp;
    private final String serverPort;
    private final String contextPath;
    private final String publisherHomepage;
    private final String contactName;
    private final String contactEmail;
    private final String licenseUri;
    private final String languageUri;

    public FairDataPointMetadataUtil(FileService fileService,
                                     @Value("${name:unnamed}") String nodeName,
                                     @Value("${desc:no description available}") String nodeDescription,
                                     @Value("${node.ip:}") String nodeIp,
                                     @Value("${server.port:8080}") String serverPort,
                                     @Value("${server.servlet.context-path:/taniwha}") String contextPath,
                                     @Value("${fairdatapoint.publisher-homepage:}") String publisherHomepage,
                                     @Value("${fairdatapoint.contact-name:}") String contactName,
                                     @Value("${fairdatapoint.contact-email:}") String contactEmail,
                                     @Value("${fairdatapoint.license-uri:" + DEFAULT_LICENSE + "}") String licenseUri,
                                     @Value("${fairdatapoint.language-uri:" + DEFAULT_LANGUAGE + "}") String languageUri) {
        this.fileService = fileService;
        this.nodeName = isBlank(nodeName) ? "unnamed" : nodeName.trim();
        this.nodeDescription = isBlank(nodeDescription) ? "no description available" : nodeDescription.trim();
        this.nodeIp = nodeIp == null ? "" : nodeIp.trim();
        this.serverPort = isBlank(serverPort) ? "8080" : serverPort.trim();
        this.contextPath = normalizeContextPath(contextPath);
        this.publisherHomepage = publisherHomepage == null ? "" : publisherHomepage.trim();
        this.contactName = contactName == null ? "" : contactName.trim();
        this.contactEmail = contactEmail == null ? "" : contactEmail.trim();
        this.licenseUri = isBlank(licenseUri) ? DEFAULT_LICENSE : licenseUri.trim();
        this.languageUri = isBlank(languageUri) ? DEFAULT_LANGUAGE : languageUri.trim();
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

        for (DatasetGroup group : groupDatasetFiles(datasetFiles)) {
            String datasetSlug = slugify(group.logicalStem());
            String datasetTitle = humanize(group.logicalStem());
            Resource dataset = model.createResource(nodeBaseUrl + "/fdp/dataset/" + datasetSlug);
            addCommonResourceFields(
                    model,
                    dataset,
                    group.logicalStem(),
                    datasetTitle,
                    describeDatasetGroup(group)
            );
            dataset.addProperty(RDF.type, model.createResource(DCAT + "Dataset"));
            dataset.addProperty(identifierProperty, group.logicalStem());
            dataset.addProperty(themeProperty, model.createResource(HEALTH_THEME));
            dataset.addProperty(isPartOfProperty, catalog);
            dataset.addProperty(accessRightsProperty, restrictedAccessRights(model, nodeBaseUrl));
            dataset.addProperty(model.createProperty(DCT + "language"), model.createResource(languageUri));
            dataset.addProperty(model.createProperty(DCT + "license"), model.createResource(licenseUri));
            addContactPoint(model, dataset);
            addTemporalMetadata(model, dataset, group.datasetFiles());
            catalog.addProperty(datasetProperty, dataset);

            for (String datasetFile : group.datasetFiles()) {
                String stem = stripExtension(datasetFile);
                String slug = slugify(stem);
                String distributionTitle = humanize(stem) + " distribution";

                Resource distribution = model.createResource(nodeBaseUrl + "/fdp/distribution/" + slug);
                addCommonResourceFields(
                        model,
                        distribution,
                        stem + "-distribution",
                        distributionTitle,
                        "Access instructions for dataset file " + datasetFile + ". Raw file access requires node authorization."
                );
                distribution.addProperty(RDF.type, model.createResource(DCAT + "Distribution"));
                distribution.addProperty(isPartOfProperty, dataset);
                distribution.addProperty(accessRightsProperty, restrictedAccessRights(model, nodeBaseUrl));
                distribution.addProperty(model.createProperty(DCT + "license"), model.createResource(licenseUri));
                distribution.addProperty(model.createProperty(DCT + "format"), model.createResource(resolveFormatUri(datasetFile)));
                addTemporalMetadata(model, distribution, List.of(datasetFile));
                addContactPoint(model, distribution);

                String accessUrl = nodeBaseUrl + "/fdp/access/" + UriUtils.encodePathSegment(slug, StandardCharsets.UTF_8);
                distribution.addProperty(accessUrlProperty, model.createResource(accessUrl));
                distribution.addProperty(mediaTypeProperty, resolveMediaTypeValue(datasetFile));

                dataset.addProperty(distributionProperty, distribution);
            }
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
        publisher.addProperty(RDF.type, model.createResource(FOAF + "Organization"));
        publisher.addProperty(foafNameProperty, nodeName);
        if (!isBlank(publisherHomepage)) {
            publisher.addProperty(model.createProperty(FOAF + "homepage"), model.createResource(publisherHomepage));
        } else {
            publisher.addProperty(model.createProperty(FOAF + "homepage"), model.createResource(resolveNodeBaseUrl()));
        }
        resource.addProperty(publisherProperty, publisher);
    }

    private void addContactPoint(Model model, Resource resource) {
        if (isBlank(contactName) && isBlank(contactEmail)) {
            return;
        }

        Resource contact = model.createResource();
        contact.addProperty(RDF.type, model.createResource(VCARD + "Individual"));
        if (!isBlank(contactName)) {
            contact.addProperty(model.createProperty(VCARD + "fn"), contactName);
        }
        if (!isBlank(contactEmail)) {
            String value = contactEmail.startsWith("mailto:") ? contactEmail : "mailto:" + contactEmail;
            contact.addProperty(model.createProperty(VCARD + "hasEmail"), value);
        }
        resource.addProperty(model.createProperty(DCAT + "contactPoint"), contact);
    }

    private void addTemporalMetadata(Model model, Resource resource, List<String> datasetFiles) {
        Instant issued = null;
        Instant modified = null;

        for (String datasetFile : datasetFiles) {
            try {
                Path path = fileService.resolveDatasetFilePath(datasetFile);
                Instant fileInstant = Files.getLastModifiedTime(path).toInstant();
                if (issued == null || fileInstant.isBefore(issued)) {
                    issued = fileInstant;
                }
                if (modified == null || fileInstant.isAfter(modified)) {
                    modified = fileInstant;
                }
            } catch (Exception ignored) {
                // Leave timestamps absent if file metadata cannot be resolved.
            }
        }

        Property issuedProperty = model.createProperty(DCT + "issued");
        Property modifiedProperty = model.createProperty(DCT + "modified");
        if (issued != null) {
            resource.addLiteral(issuedProperty, ResourceFactory.createTypedLiteral(issued.toString(), XSDDatatype.XSDdateTime));
        }
        if (modified != null) {
            resource.addLiteral(modifiedProperty, ResourceFactory.createTypedLiteral(modified.toString(), XSDDatatype.XSDdateTime));
        }
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

    private String resolveFormatUri(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            return "http://publications.europa.eu/resource/authority/file-type/CSV";
        }
        if (lower.endsWith(".tsv")) {
            return "http://publications.europa.eu/resource/authority/file-type/TXT";
        }
        if (lower.endsWith(".xlsx")) {
            return "http://publications.europa.eu/resource/authority/file-type/XLSX";
        }
        return "http://publications.europa.eu/resource/authority/file-type/BIN";
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

    private List<DatasetGroup> groupDatasetFiles(List<String> datasetFiles) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String datasetFile : datasetFiles) {
            String stem = stripExtension(datasetFile);
            String logicalStem = logicalDatasetStem(stem);
            grouped.computeIfAbsent(logicalStem, ignored -> new ArrayList<>()).add(datasetFile);
        }

        List<DatasetGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            entry.getValue().sort(Comparator.comparingInt(this::distributionOrder).thenComparing(String.CASE_INSENSITIVE_ORDER));
            groups.add(new DatasetGroup(entry.getKey(), entry.getValue()));
        }
        groups.sort(Comparator.comparing(DatasetGroup::logicalStem, String.CASE_INSENSITIVE_ORDER));
        return groups;
    }

    private String logicalDatasetStem(String stem) {
        String normalized = stem == null ? "" : stem.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("parsed_") && normalized.length() > 7) {
            return normalized.substring(7);
        }
        if (lower.startsWith("parsed-") && normalized.length() > 7) {
            return normalized.substring(7);
        }
        return normalized;
    }

    private int distributionOrder(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.startsWith("parsed_") || lower.startsWith("parsed-") ? 1 : 0;
    }

    private String describeDatasetGroup(DatasetGroup group) {
        if (group.datasetFiles().size() == 1) {
            return "Dataset file exposed by the " + nodeName + " node.";
        }
        return "Logical dataset exposed by the " + nodeName + " node with "
                + group.datasetFiles().size() + " available distributions.";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record DatasetGroup(String logicalStem, List<String> datasetFiles) {
    }
}
