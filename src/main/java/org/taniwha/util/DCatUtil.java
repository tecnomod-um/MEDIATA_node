package org.taniwha.util;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.taniwha.model.Dataset;
import org.taniwha.model.Distribution;
import org.taniwha.model.NodeMetadata;
import org.taniwha.model.Variable;

import java.io.StringReader;
import java.util.*;

public final class DCatUtil {

    private DCatUtil() {}

    private static final String DCAT = "http://www.w3.org/ns/dcat#";
    private static final String DCT = "http://purl.org/dc/terms/";
    private static final String FOAF = "http://xmlns.com/foaf/0.1/";
    private static final String VCARD = "http://www.w3.org/2006/vcard/ns#";
    private static final String LOCN = "http://www.w3.org/ns/locn#";
    private static final String PROV = "http://www.w3.org/ns/prov#";
    private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";

    private static final Set<String> DATASET_KNOWN_LOCAL_NAMES = Set.of(
            "type",
            "Dataset",
            "title",
            "description",
            "identifier",
            "issued",
            "modified",
            "accrualPeriodicity",
            "keyword",
            "theme",
            "language",
            "publisher",
            "contactPoint",
            "spatial",
            "temporal",
            "distribution",
            "accessRights",
            "applicableLegislation",
            "codeValues",
            "codingSystem",
            "conformsTo",
            "custodian",
            "hasPersonalData",
            "healthDataAccessBody",
            "healthCategory",
            "healthTheme",
            "isStructured",
            "numberOfRecords",
            "numberOfUniqueIndividuals",
            "provenance",
            "variables",
            "variable",
            "hasVariable",
            "wasGeneratedBy"
    );

    private static final Set<String> DISTRIBUTION_KNOWN_LOCAL_NAMES = Set.of(
            "type",
            "title",
            "description",
            "format",
            "license",
            "downloadURL",
            "accessURL",
            "mediaType",
            "byteSize",
            "availability",
            "issued",
            "modified",
            "conformsTo",
            "accessService"
    );

    private static final Set<String> VARIABLE_KNOWN_LOCAL_NAMES = Set.of(
            "type",
            "name",
            "title",
            "label",
            "prefLabel",
            "description",
            "definition",
            "dataType",
            "datatype",
            "codingSystem",
            "codeSystem",
            "valueDomain"
    );

    public static Model readModel(String rdfContent, String fileName) {
        Model model = ModelFactory.createDefaultModel();

        String lang = guessRdfLanguage(fileName);

        try {
            model.read(new StringReader(rdfContent), null, lang);
        } catch (Exception firstFailure) {
            // Fallbacks make this tolerant of metadata files named oddly.
            List<String> fallbacks = List.of("TTL", "JSON-LD", "RDF/XML", "N-TRIPLES");

            for (String fallback : fallbacks) {
                if (fallback.equals(lang)) continue;

                try {
                    Model fallbackModel = ModelFactory.createDefaultModel();
                    fallbackModel.read(new StringReader(rdfContent), null, fallback);
                    return fallbackModel;
                } catch (Exception ignored) {
                    // Try next format.
                }
            }

            throw firstFailure;
        }

        return model;
    }

    public static NodeMetadata parseNodeMetadata(Model model, String sourceFile) {
        NodeMetadata metadata = new NodeMetadata();
        metadata.setContext("https://www.w3.org/ns/dcat.jsonld");
        metadata.setType("dcat:Catalog");
        metadata.setSourceFile(sourceFile);

        List<Dataset> datasets = new ArrayList<>();

        Resource dcatDataset = model.createResource(DCAT + "Dataset");
        ResIterator datasetResources = model.listResourcesWithProperty(RDF.type, dcatDataset);

        while (datasetResources.hasNext()) {
            Resource datasetResource = datasetResources.nextResource();
            datasets.add(parseDataset(datasetResource));
        }

        metadata.setDataset(datasets);
        return metadata;
    }

    public static Dataset parseDataset(Resource datasetResource) {
        Dataset dataset = new Dataset();

        if (datasetResource.isURIResource()) {
            dataset.setUri(datasetResource.getURI());
        }

        dataset.setTitle(firstString(datasetResource, DCT + "title", "title"));
        dataset.setDescription(firstString(datasetResource, DCT + "description", "description"));
        dataset.setIdentifier(firstString(datasetResource, DCT + "identifier", "identifier"));
        dataset.setIssued(firstString(datasetResource, DCT + "issued", "issued"));
        dataset.setModified(firstString(datasetResource, DCT + "modified", "modified"));
        dataset.setAccrualPeriodicity(firstString(datasetResource, DCT + "accrualPeriodicity", "accrualPeriodicity"));

        dataset.setKeyword(values(datasetResource, DCAT + "keyword", "keyword"));
        dataset.setTheme(values(datasetResource, DCAT + "theme", "theme"));
        dataset.setLanguage(values(datasetResource, DCT + "language", "language"));

        dataset.setPublisher(firstObject(datasetResource, DCT + "publisher", "publisher"));
        dataset.setContactPoint(firstObject(datasetResource, DCAT + "contactPoint", "contactPoint"));

        dataset.setSpatial(firstObject(datasetResource, DCT + "spatial", "spatial", "geographicalCoverage", "geographicCoverage"));
        dataset.setTemporal(firstObject(datasetResource, DCT + "temporal", "temporal", "temporalCoverage"));

        dataset.setDistribution(parseDistributions(datasetResource));

        dataset.setAccessRights(firstObject(datasetResource, DCT + "accessRights", "accessRights"));
        dataset.setApplicableLegislation(values(datasetResource, null, "applicableLegislation"));
        dataset.setCodeValues(values(datasetResource, null, "codeValues", "codeValue"));
        dataset.setCodingSystem(values(datasetResource, null, "codingSystem", "codeSystem"));
        dataset.setConformsTo(values(datasetResource, DCT + "conformsTo", "conformsTo"));
        dataset.setCustodian(firstObject(datasetResource, null, "custodian"));
        dataset.setHasPersonalData(firstBoolean(datasetResource, null, "hasPersonalData"));
        dataset.setHealthDataAccessBody(firstObject(datasetResource, null, "healthDataAccessBody"));
        dataset.setHealthCategory(values(datasetResource, null, "healthCategory", "healthDataCategory"));
        dataset.setHealthTheme(values(datasetResource, null, "healthTheme"));
        dataset.setIsStructured(firstBoolean(datasetResource, null, "isStructured"));
        dataset.setNumberOfRecords(firstLong(datasetResource, null, "numberOfRecords", "recordCount"));
        dataset.setNumberOfUniqueIndividuals(firstLong(datasetResource, null, "numberOfUniqueIndividuals", "uniqueIndividuals"));
        dataset.setProvenance(firstObject(datasetResource, DCT + "provenance", "provenance"));
        dataset.setType(values(datasetResource, DCT + "type", "type"));
        dataset.setVariables(parseVariables(datasetResource));
        dataset.setWasGeneratedBy(firstObject(datasetResource, PROV + "wasGeneratedBy", "wasGeneratedBy"));

        captureAdditionalProperties(
                datasetResource,
                dataset.getAdditionalProperties(),
                DATASET_KNOWN_LOCAL_NAMES
        );

        return dataset;
    }

    private static List<Distribution> parseDistributions(Resource datasetResource) {
        List<Distribution> distributions = new ArrayList<>();

        List<Resource> distributionResources = resourceObjects(datasetResource, DCAT + "distribution", "distribution");

        for (Resource distributionResource : distributionResources) {
            Distribution distribution = new Distribution();

            if (distributionResource.isURIResource()) {
                distribution.setUri(distributionResource.getURI());
            }

            distribution.setTitle(firstString(distributionResource, DCT + "title", "title"));
            distribution.setDescription(firstString(distributionResource, DCT + "description", "description"));

            distribution.setFormat(firstObject(distributionResource, DCT + "format", "format"));
            distribution.setLicense(firstObject(distributionResource, DCT + "license", "license"));
            distribution.setDownloadURL(firstObject(distributionResource, DCAT + "downloadURL", "downloadURL"));

            distribution.setAccessURL(firstObject(distributionResource, DCAT + "accessURL", "accessURL"));
            distribution.setMediaType(firstObject(distributionResource, DCAT + "mediaType", "mediaType"));
            distribution.setByteSize(firstObject(distributionResource, DCAT + "byteSize", "byteSize"));
            distribution.setAvailability(firstObject(distributionResource, DCAT + "availability", "availability"));
            distribution.setIssued(firstString(distributionResource, DCT + "issued", "issued"));
            distribution.setModified(firstString(distributionResource, DCT + "modified", "modified"));
            distribution.setConformsTo(firstObject(distributionResource, DCT + "conformsTo", "conformsTo"));
            distribution.setAccessService(firstObject(distributionResource, DCAT + "accessService", "accessService"));

            captureAdditionalProperties(
                    distributionResource,
                    distribution.getAdditionalProperties(),
                    DISTRIBUTION_KNOWN_LOCAL_NAMES
            );

            distributions.add(distribution);
        }

        return distributions.isEmpty() ? null : distributions;
    }

    private static List<Variable> parseVariables(Resource datasetResource) {
        List<Variable> variables = new ArrayList<>();

        List<Resource> variableResources = resourceObjects(
                datasetResource,
                null,
                "variables",
                "variable",
                "hasVariable"
        );

        for (Resource variableResource : variableResources) {
            Variable variable = new Variable();

            if (variableResource.isURIResource()) {
                variable.setUri(variableResource.getURI());
            }

            variable.setName(firstString(variableResource, null, "name", "title", "label", "prefLabel"));
            variable.setDefinition(firstString(variableResource, null, "definition", "description"));
            variable.setDataType(firstObject(variableResource, null, "dataType", "datatype"));
            variable.setCodingSystem(firstObject(variableResource, null, "codingSystem", "codeSystem"));
            variable.setValueDomain(firstObject(variableResource, null, "valueDomain"));

            captureAdditionalProperties(
                    variableResource,
                    variable.getAdditionalProperties(),
                    VARIABLE_KNOWN_LOCAL_NAMES
            );

            variables.add(variable);
        }

        return variables.isEmpty() ? null : variables;
    }

    private static String firstString(Resource subject, String exactUri, String... localNames) {
        Object value = firstObject(subject, exactUri, localNames);
        return value == null ? null : String.valueOf(value);
    }

    private static Object firstObject(Resource subject, String exactUri, String... localNames) {
        Statement exactStatement = exactUri == null ? null : subject.getProperty(property(exactUri));

        if (exactStatement != null) {
            return nodeToObject(exactStatement.getObject(), 0, new HashSet<>());
        }

        StmtIterator iterator = subject.listProperties();

        while (iterator.hasNext()) {
            Statement statement = iterator.nextStatement();
            String localName = localName(statement.getPredicate().getURI());

            if (matches(localName, localNames)) {
                return nodeToObject(statement.getObject(), 0, new HashSet<>());
            }
        }

        return null;
    }

    private static List<Object> values(Resource subject, String exactUri, String... localNames) {
        List<Object> values = new ArrayList<>();

        if (exactUri != null) {
            StmtIterator exactIterator = subject.listProperties(property(exactUri));

            while (exactIterator.hasNext()) {
                values.add(nodeToObject(exactIterator.nextStatement().getObject(), 0, new HashSet<>()));
            }
        }

        StmtIterator iterator = subject.listProperties();

        while (iterator.hasNext()) {
            Statement statement = iterator.nextStatement();
            String predicateUri = statement.getPredicate().getURI();
            String localName = localName(predicateUri);

            if (exactUri != null && exactUri.equals(predicateUri)) {
                continue;
            }

            if (matches(localName, localNames)) {
                values.add(nodeToObject(statement.getObject(), 0, new HashSet<>()));
            }
        }

        return values.isEmpty() ? null : values;
    }

    private static Boolean firstBoolean(Resource subject, String exactUri, String... localNames) {
        Object value = firstObject(subject, exactUri, localNames);
        if (value == null) return null;

        String text = String.valueOf(value).trim();

        if ("true".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text)) return false;
        if ("1".equals(text)) return true;
        if ("0".equals(text)) return false;

        return null;
    }

    private static Long firstLong(Resource subject, String exactUri, String... localNames) {
        Object value = firstObject(subject, exactUri, localNames);
        if (value == null) return null;

        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Resource> resourceObjects(Resource subject, String exactUri, String... localNames) {
        List<Resource> resources = new ArrayList<>();

        if (exactUri != null) {
            StmtIterator exactIterator = subject.listProperties(property(exactUri));

            while (exactIterator.hasNext()) {
                RDFNode object = exactIterator.nextStatement().getObject();
                if (object.isResource()) {
                    resources.add(object.asResource());
                }
            }
        }

        StmtIterator iterator = subject.listProperties();

        while (iterator.hasNext()) {
            Statement statement = iterator.nextStatement();
            String predicateUri = statement.getPredicate().getURI();
            String localName = localName(predicateUri);

            if (exactUri != null && exactUri.equals(predicateUri)) {
                continue;
            }

            if (matches(localName, localNames) && statement.getObject().isResource()) {
                resources.add(statement.getObject().asResource());
            }
        }

        return resources;
    }

    private static Object nodeToObject(RDFNode node, int depth, Set<String> visited) {
        if (node == null) return null;

        if (node.isLiteral()) {
            return node.asLiteral().getString();
        }

        if (!node.isResource()) {
            return node.toString();
        }

        Resource resource = node.asResource();

        if (depth >= 3) {
            if (resource.isURIResource()) return resource.getURI();
            return resource.toString();
        }

        String visitKey = resource.isURIResource() ? resource.getURI() : resource.getId().toString();

        if (visited.contains(visitKey)) {
            if (resource.isURIResource()) return resource.getURI();
            return resource.toString();
        }

        visited.add(visitKey);

        boolean hasProperties = resource.listProperties().hasNext();

        if (resource.isURIResource() && !hasProperties) {
            return resource.getURI();
        }

        Map<String, Object> object = new LinkedHashMap<>();

        if (resource.isURIResource()) {
            object.put("uri", resource.getURI());
        }

        StmtIterator iterator = resource.listProperties();

        while (iterator.hasNext()) {
            Statement statement = iterator.nextStatement();
            String key = compactPropertyName(statement.getPredicate().getURI());
            Object value = nodeToObject(statement.getObject(), depth + 1, visited);
            addMultiValue(object, key, value);
        }

        if (object.isEmpty() && resource.isURIResource()) {
            return resource.getURI();
        }

        if (object.isEmpty()) {
            return resource.toString();
        }

        return object;
    }

    private static void captureAdditionalProperties(
            Resource subject,
            Map<String, Object> target,
            Set<String> knownLocalNames
    ) {
        StmtIterator iterator = subject.listProperties();

        while (iterator.hasNext()) {
            Statement statement = iterator.nextStatement();
            String predicateUri = statement.getPredicate().getURI();
            String localName = localName(predicateUri);

            if (knownLocalNames.contains(localName)) {
                continue;
            }

            String key = compactPropertyName(predicateUri);
            Object value = nodeToObject(statement.getObject(), 0, new HashSet<>());

            addMultiValue(target, key, value);
        }
    }

    private static void addMultiValue(Map<String, Object> target, String key, Object value) {
        if (value == null) return;

        Object existing = target.get(key);

        if (existing == null) {
            target.put(key, value);
            return;
        }

        if (existing instanceof List<?>) {
            List<Object> list = new ArrayList<>((List<?>) existing);
            list.add(value);
            target.put(key, list);
            return;
        }

        List<Object> list = new ArrayList<>();
        list.add(existing);
        list.add(value);
        target.put(key, list);
    }

    private static boolean matches(String actualLocalName, String... expectedLocalNames) {
        if (actualLocalName == null || expectedLocalNames == null) {
            return false;
        }

        for (String expected : expectedLocalNames) {
            if (expected != null && expected.equals(actualLocalName)) {
                return true;
            }
        }

        return false;
    }

    private static Property property(String uri) {
        return ResourceFactory.createProperty(uri);
    }

    private static String compactPropertyName(String uri) {
        if (uri == null) return "unknown";

        if (uri.startsWith(DCAT)) return "dcat:" + uri.substring(DCAT.length());
        if (uri.startsWith(DCT)) return "dct:" + uri.substring(DCT.length());
        if (uri.startsWith(FOAF)) return "foaf:" + uri.substring(FOAF.length());
        if (uri.startsWith(VCARD)) return "vcard:" + uri.substring(VCARD.length());
        if (uri.startsWith(LOCN)) return "locn:" + uri.substring(LOCN.length());
        if (uri.startsWith(PROV)) return "prov:" + uri.substring(PROV.length());
        if (uri.startsWith(RDFS)) return "rdfs:" + uri.substring(RDFS.length());
        if (uri.startsWith(SKOS)) return "skos:" + uri.substring(SKOS.length());

        return localName(uri);
    }

    private static String localName(String uri) {
        if (uri == null || uri.isBlank()) return uri;

        int hash = uri.lastIndexOf('#');
        if (hash >= 0 && hash < uri.length() - 1) {
            return uri.substring(hash + 1);
        }

        int slash = uri.lastIndexOf('/');
        if (slash >= 0 && slash < uri.length() - 1) {
            return uri.substring(slash + 1);
        }

        return uri;
    }

    private static String guessRdfLanguage(String fileName) {
        if (fileName == null) return "TTL";

        String lower = fileName.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".ttl") || lower.endsWith(".turtle")) return "TTL";
        if (lower.endsWith(".jsonld") || lower.endsWith(".json")) return "JSON-LD";
        if (lower.endsWith(".rdf") || lower.endsWith(".xml") || lower.endsWith(".owl")) return "RDF/XML";
        if (lower.endsWith(".nt")) return "N-TRIPLES";

        return "TTL";
    }
}