package org.taniwha.service.semanticcde;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.springframework.stereotype.Service;
import org.taniwha.dto.SemanticCdeConversionDTO;
import org.taniwha.dto.SemanticCdeDatasetMatchDTO;
import org.taniwha.dto.SemanticCdeProcessRequestDTO;
import org.taniwha.dto.SemanticCdeProcessResponseDTO;
import org.taniwha.service.FileService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SemanticCdeProcessingService {

    private static final Pattern SAFE_MAPPING_ID = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final Pattern SAFE_OUTPUT_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern REGISTRY_LINK = Pattern.compile(
            "<a[^>]+href=[\"'](scde-index/[^\"']+\\.scde\\.ttl)[\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern CONVERSION_IDENTIFIER = Pattern.compile("scde:identifier\\s+\"(DERIVE-[^\"]+)\"");
    private static final Pattern SEMANTIC_LITERAL = Pattern.compile(
            "scde:(?:designationText|definitionText)\\s+\"((?:\\\\\"|[^\"])*)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TEMPLATE_REFERENCE = Pattern.compile("\\{([^{}]+)}");
    private static final String SCDE_NAMESPACE = "urn:semantic-cde:vocab:";
    private static final String RML_NAMESPACE = "http://semweb.mmlab.be/ns/rml#";
    private static final String RR_NAMESPACE = "http://www.w3.org/ns/r2rml#";
    private static final Property SCDE_IDENTIFIER = ResourceFactory.createProperty(SCDE_NAMESPACE, "identifier");
    private static final Property SCDE_RULE = ResourceFactory.createProperty(SCDE_NAMESPACE, "rule");
    private static final Property SCDE_REFERENCE = ResourceFactory.createProperty(SCDE_NAMESPACE, "reference");
    private static final Property SCDE_DOCUMENT_REFERENCE = ResourceFactory.createProperty(SCDE_NAMESPACE, "documentReference");
    private static final Property SCDE_URI = ResourceFactory.createProperty(SCDE_NAMESPACE, "uri");
    private static final Property SCDE_NOTATION = ResourceFactory.createProperty(SCDE_NAMESPACE, "notation");
    private static final Property RML_REFERENCE = ResourceFactory.createProperty(RML_NAMESPACE, "reference");
    private static final Property RR_TEMPLATE = ResourceFactory.createProperty(RR_NAMESPACE, "template");
    private static final Set<String> FIELD_STOP_TOKENS = Set.of("value", "values", "code", "text", "name");
    private static final Set<String> SEMANTIC_METADATA_TOKENS = Set.of(
            "id", "identifier", "patient", "observation", "performer", "device", "date", "time",
            "datetime", "timestamp", "unit", "body", "site", "system", "code", "label", "display",
            "value", "values", "text", "name", "source"
    );
    private static final Map<String, List<String>> SHAPE_TOKEN_SYNONYMS = Map.of(
            "id", List.of("identifier"),
            "ids", List.of("identifier"),
            "datetime", List.of("date", "time"),
            "timestamp", List.of("date", "time"),
            "bp", List.of("blood", "pressure"),
            "sys", List.of("systolic"),
            "dia", List.of("diastolic")
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private final FileService fileService;
    private final SemanticCdeProcessorProperties properties;
    private final SemanticCdeProcessorClient processorClient;

    public SemanticCdeProcessingService(
            FileService fileService,
            SemanticCdeProcessorProperties properties,
            SemanticCdeProcessorClient processorClient
    ) {
        this.fileService = fileService;
        this.properties = properties;
        this.processorClient = processorClient;
    }

    public List<SemanticCdeDatasetMatchDTO> findRegistryMatches(String fileName) {
        DatasetShape shape = inspectDatasetShape(fileName);
        List<ScoredRegistryMatch> matches = new ArrayList<>();
        for (RegistryEntry entry : discoverRegistryEntries()) {
            List<SemanticCdeConversionDTO> conversions = entry.conversions().stream()
                    .filter(conversion -> sourceMatchesDatasetShape(shape, conversion.source()))
                    .filter(conversion -> mappingAcceptsDatasetShape(shape, conversion, entry))
                    .toList();
            List<SemanticCdeConversionDTO> runnableConversions = buildRunnableConversions(
                    conversions,
                    entry.conversions()
            );
            if (runnableConversions.isEmpty()) {
                continue;
            }

            CandidateScore candidateScore = scoreRegistryEntry(shape, entry, runnableConversions);
            if (!candidateScore.accepted()) {
                continue;
            }
            matches.add(new ScoredRegistryMatch(
                    new SemanticCdeDatasetMatchDTO(
                            shape.fileName(),
                            entry.title(),
                            entry.semanticCdePath(),
                            runnableConversions,
                            datasetSemanticFields(shape),
                            registrySemanticDescriptions(entry, runnableConversions),
                            candidateScore.score(),
                            candidateScore.lexicallyPlausible()
                    ),
                    candidateScore.score()
            ));
        }

        return matches.stream()
                .sorted(Comparator.comparingDouble(ScoredRegistryMatch::score).reversed()
                        .thenComparing(match -> match.match().title()))
                .map(ScoredRegistryMatch::match)
                .toList();
    }

    private DatasetShape inspectDatasetShape(String fileName) {
        try {
            Path inputFile = fileService.resolveDatasetFilePath(fileName);
            String sourceFormat = detectSourceFormat(inputFile);
            List<String> fields = switch (sourceFormat) {
                case "csv" -> readDelimitedHeader(inputFile, ',');
                case "tsv" -> readDelimitedHeader(inputFile, '\t');
                case "json" -> readJsonFields(inputFile);
                default -> List.of();
            };
            Set<String> unitValues = switch (sourceFormat) {
                case "csv" -> readUnitValues(inputFile, ',', fields);
                case "tsv" -> readUnitValues(inputFile, '\t', fields);
                default -> Set.of();
            };

            return new DatasetShape(fileName, sourceFormat, fields, unitValues);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to inspect dataset shape.", e);
        }
    }

    public SemanticCdeProcessResponseDTO process(SemanticCdeProcessRequestDTO request) {
        try {
            Path inputFile = fileService.resolveDatasetFilePath(request.datasetFileName());
            String semanticCdeResource = resolveSemanticCdeResource(request.semanticCdePath());
            List<String> mappingIds = validateMappingIds(request.mappingIds());
            Path outputBase = semanticCdeOutputBase(inputFile);
            Path outputPath = resolveOutputPath(request.outputFileName(), outputBase);
            Map<String, OutputState> existingOutputs = snapshotOutputFiles(outputBase);

            Files.createDirectories(outputDirectoryFor(outputPath));

            SemanticCdeProcessorResult result = processorClient.execute(new SemanticCdeProcessorCommand(
                    semanticCdeResource,
                    inputFile,
                    mappingIds,
                    outputPath
            ));

            List<String> outputFiles = result.success()
                    ? resolveProducedOutputs(outputPath, existingOutputs, outputBase)
                    : List.of();

            return new SemanticCdeProcessResponseDTO(
                    result.success(),
                    request.datasetFileName(),
                    request.semanticCdePath(),
                    mappingIds,
                    outputFiles,
                    result.success() ? "Semantic-CDE processing completed." : "Semantic-CDE processing failed.",
                    result.log()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Semantic-CDE processing was interrupted.", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Semantic-CDE processing failed because of an I/O error.", e);
        }
    }

    private String detectSourceFormat(Path inputFile) throws IOException {
        String firstLine;
        try (BufferedReader reader = Files.newBufferedReader(inputFile)) {
            firstLine = firstNonBlankLine(reader);
        }

        if (firstLine != null) {
            String trimmed = firstLine.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return "json";
            if (trimmed.startsWith("@prefix") || trimmed.startsWith("@base") || trimmed.contains(" a ")) return "sulo";
            if (parseDelimitedHeader(trimmed, '\t').size() > 1) return "tsv";
            if (parseDelimitedHeader(trimmed, ',').size() > 1) return "csv";
        }

        String lowerName = inputFile.getFileName().toString().toLowerCase();
        if (lowerName.endsWith(".csv")) return "csv";
        if (lowerName.endsWith(".tsv")) return "tsv";
        if (lowerName.endsWith(".json") || lowerName.endsWith(".jsonl")) return "json";
        if (lowerName.endsWith(".ttl") || lowerName.endsWith(".rdf")) return "sulo";
        return "unknown";
    }

    private List<String> readDelimitedHeader(Path inputFile, char delimiter) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(inputFile)) {
            String header = firstNonBlankLine(reader);
            if (header == null) {
                return List.of();
            }
            return parseDelimitedHeader(header, delimiter);
        }
    }

    private Set<String> readUnitValues(Path inputFile, char delimiter, List<String> fields) throws IOException {
        int unitIndex = -1;
        for (int index = 0; index < fields.size(); index++) {
            String normalized = normalizeText(fields.get(index));
            if ("unit".equals(normalized) || normalized.startsWith("unit ") || normalized.endsWith(" unit")) {
                unitIndex = index;
                break;
            }
        }
        if (unitIndex < 0) {
            return Set.of();
        }

        Set<String> values = new java.util.LinkedHashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(inputFile)) {
            firstNonBlankLine(reader);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> row = parseDelimitedHeader(line, delimiter);
                if (unitIndex < row.size() && !row.get(unitIndex).isBlank()) {
                    values.add(row.get(unitIndex).trim());
                }
            }
        }
        return Set.copyOf(values);
    }

    private String firstNonBlankLine(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isBlank()) {
                return line.replace("\uFEFF", "");
            }
        }
        return null;
    }

    private List<String> parseDelimitedHeader(String header, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < header.length(); i++) {
            char current = header.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < header.length() && header.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == delimiter && !quoted) {
                addHeaderField(fields, field);
            } else {
                field.append(current);
            }
        }
        addHeaderField(fields, field);

        return fields;
    }

    private void addHeaderField(List<String> fields, StringBuilder field) {
        String cleaned = field.toString().trim();
        if (!cleaned.isBlank()) {
            fields.add(cleaned);
        }
        field.setLength(0);
    }

    private List<String> readJsonFields(Path inputFile) throws IOException {
        String content = Files.readString(inputFile);
        if (content.isBlank()) {
            return List.of();
        }

        JsonNode root = OBJECT_MAPPER.readTree(content);
        JsonNode sample = root.isArray() && !root.isEmpty() ? root.get(0) : root;
        if (!sample.isObject()) {
            return List.of();
        }

        List<String> fields = new ArrayList<>();
        sample.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private List<RegistryEntry> discoverRegistryEntries() {
        String repositoryUrl = properties.semanticCdeRepositoryUrl();
        if (repositoryUrl == null) {
            return List.of();
        }

        String index = fetchText(repositoryUrl + "/index.html");
        var matcher = REGISTRY_LINK.matcher(index);
        Map<String, RegistryLink> links = new LinkedHashMap<>();
        while (matcher.find()) {
            String title = htmlText(matcher.group(2));
            String semanticCdePath = matcher.group(1);
            if (!title.isBlank() && !semanticCdePath.isBlank()) {
                links.putIfAbsent(semanticCdePath, new RegistryLink(title, semanticCdePath));
            }
        }

        return links.values().stream()
                .map(link -> {
                    String registryText = fetchText(repositoryUrl + "/" + link.semanticCdePath());
                    return new RegistryEntry(
                            link.title(),
                            link.semanticCdePath(),
                            registryText,
                            parseSemanticCdeConversions(registryText),
                            parseMappingArtifacts(registryText)
                    );
                })
                .filter(entry -> !entry.conversions().isEmpty())
                .toList();
    }

    private String fetchText(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "text/turtle,text/html,text/plain,*/*")
                    .timeout(java.time.Duration.ofSeconds(Math.min(properties.timeoutSeconds(), 30)))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Semantic-CDE registry returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading Semantic-CDE registry.", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read Semantic-CDE registry.", e);
        }
    }

    private String htmlText(String value) {
        return value == null ? "" : value.replaceAll("<[^>]+>", "").trim();
    }

    private List<SemanticCdeConversionDTO> parseSemanticCdeConversions(String ttlText) {
        var matcher = CONVERSION_IDENTIFIER.matcher(ttlText);
        List<SemanticCdeConversionDTO> conversions = new ArrayList<>();
        while (matcher.find()) {
            String id = matcher.group(1);
            String before = ttlText.substring(Math.max(0, matcher.start() - 900), matcher.start());
            String label = extractNearestLiteral(before, "scde:designationText");
            String description = extractNearestLiteral(before, "scde:definitionText");
            ConversionLabel parsed = parseConversionLabel(label);
            if (!label.isBlank() && !parsed.source().isBlank() && !parsed.target().isBlank()) {
                conversions.add(new SemanticCdeConversionDTO(
                        id,
                        label,
                        description,
                        parsed.source(),
                        parsed.target(),
                        outputSuffix(parsed.target()),
                        List.of(id)
                ));
            }
        }
        return conversions;
    }

    private Map<String, MappingArtifact> parseMappingArtifacts(String ttlText) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString(ttlText).lang(Lang.TURTLE).parse(model);

        Map<String, MappingArtifact> artifacts = new LinkedHashMap<>();
        model.listStatements(null, SCDE_IDENTIFIER, (RDFNode) null).forEachRemaining(identifierStatement -> {
            if (!identifierStatement.getObject().isLiteral()) {
                return;
            }
            String identifier = identifierStatement.getString();
            if (!identifier.startsWith("DERIVE-")) {
                return;
            }

            Resource scopedIdentifier = identifierStatement.getSubject();
            Resource derivation = firstSubject(model, SCDE_IDENTIFIER, scopedIdentifier);
            Resource rule = firstResourceObject(derivation, SCDE_RULE);
            Resource reference = firstResourceObject(rule, SCDE_REFERENCE);
            Resource document = firstResourceObject(reference, SCDE_DOCUMENT_REFERENCE);
            Resource artifactUri = firstResourceObject(document, SCDE_URI);
            if (artifactUri != null && artifactUri.getURI() != null) {
                String notation = firstLiteralObject(rule, SCDE_NOTATION);
                artifacts.put(identifier, new MappingArtifact(artifactUri.getURI(), notation));
            }
        });
        return Map.copyOf(artifacts);
    }

    private Resource firstSubject(Model model, Property property, RDFNode object) {
        var statements = model.listStatements(null, property, object);
        try {
            return statements.hasNext() ? statements.nextStatement().getSubject() : null;
        } finally {
            statements.close();
        }
    }

    private Resource firstResourceObject(Resource subject, Property property) {
        if (subject == null) {
            return null;
        }
        var statement = subject.getProperty(property);
        return statement != null && statement.getObject().isResource()
                ? statement.getResource()
                : null;
    }

    private String firstLiteralObject(Resource subject, Property property) {
        if (subject == null) {
            return "";
        }
        var statement = subject.getProperty(property);
        return statement != null && statement.getObject().isLiteral()
                ? statement.getString()
                : "";
    }

    private boolean mappingAcceptsDatasetShape(
            DatasetShape shape,
            SemanticCdeConversionDTO conversion,
            RegistryEntry entry
    ) {
        if (!("csv".equals(shape.sourceFormat()) || "tsv".equals(shape.sourceFormat()))) {
            return true;
        }

        MappingArtifact artifact = entry.mappingArtifacts().get(conversion.id());
        if (artifact == null || !"rml".equalsIgnoreCase(artifact.notation())) {
            return true;
        }

        Set<String> requiredFields = parseRmlSourceFields(fetchText(artifact.uri()));
        return requiredFields.isEmpty() || Set.copyOf(shape.fields()).containsAll(requiredFields);
    }

    private Set<String> parseRmlSourceFields(String mappingText) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString(mappingText).lang(Lang.TURTLE).parse(model);

        Set<String> fields = new java.util.LinkedHashSet<>();
        model.listObjectsOfProperty(RML_REFERENCE).forEachRemaining(node -> {
            if (node.isLiteral() && !node.asLiteral().getString().isBlank()) {
                fields.add(node.asLiteral().getString());
            }
        });
        model.listObjectsOfProperty(RR_TEMPLATE).forEachRemaining(node -> {
            if (!node.isLiteral()) {
                return;
            }
            var matcher = TEMPLATE_REFERENCE.matcher(node.asLiteral().getString());
            while (matcher.find()) {
                fields.add(matcher.group(1));
            }
        });
        return Set.copyOf(fields);
    }

    private List<SemanticCdeConversionDTO> buildRunnableConversions(
            List<SemanticCdeConversionDTO> sourceConversions,
            List<SemanticCdeConversionDTO> allConversions
    ) {
        List<SemanticCdeConversionDTO> runnable = new ArrayList<>(sourceConversions);
        List<SemanticCdeConversionDTO> suloOutputs = allConversions.stream()
                .filter(conversion -> "sulo".equals(normalizeText(conversion.source())))
                .toList();

        for (SemanticCdeConversionDTO sourceConversion : sourceConversions) {
            if (!"sulo".equals(normalizeText(sourceConversion.target()))) {
                continue;
            }
            for (SemanticCdeConversionDTO suloOutput : suloOutputs) {
                runnable.add(new SemanticCdeConversionDTO(
                        sourceConversion.id() + "__" + suloOutput.id(),
                        sourceConversion.source() + " to " + suloOutput.target() + " via SULO",
                        sourceConversion.description() + " Then " + suloOutput.description(),
                        sourceConversion.source(),
                        suloOutput.target(),
                        outputSuffix(suloOutput.target()),
                        List.of(sourceConversion.id(), suloOutput.id())
                ));
            }
        }

        return runnable.stream()
                .collect(Collectors.toMap(
                        SemanticCdeConversionDTO::id,
                        conversion -> conversion,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .toList();
    }

    private String extractNearestLiteral(String text, String property) {
        var matcher = Pattern.compile(property + "\\s+\"((?:\\\\\"|[^\"])*)\"").matcher(text);
        String literal = "";
        while (matcher.find()) {
            literal = matcher.group(1);
        }
        return literal.replace("\\\"", "\"").replace("\\n", "\n");
    }

    private ConversionLabel parseConversionLabel(String label) {
        String normalized = String.valueOf(label).replaceFirst("(?i)\\s+derivation$", "").trim();
        var matcher = Pattern.compile("^(.+?)\\s+to\\s+(.+?)(?:\\s+.+)?$", Pattern.CASE_INSENSITIVE)
                .matcher(normalized);
        if (!matcher.find()) {
            return new ConversionLabel("", normalized.isBlank() ? "target" : normalized);
        }
        return new ConversionLabel(matcher.group(1).trim(), matcher.group(2).trim());
    }

    private String outputSuffix(String target) {
        String suffix = normalizeText(target).replace(" ", "-");
        return suffix.isBlank() ? "semantic-cde" : suffix;
    }

    private boolean sourceMatchesDatasetShape(DatasetShape shape, String source) {
        String sourceFormat = String.valueOf(shape.sourceFormat()).toLowerCase();
        String normalizedSource = normalizeText(source);
        String fieldText = normalizeText(String.join(" ", shape.fields()));

        if ("csv".equals(normalizedSource)) return "csv".equals(sourceFormat) || "tsv".equals(sourceFormat);
        if ("sulo".equals(normalizedSource)) return "sulo".equals(sourceFormat);
        if (normalizedSource.contains("fhir")) return "json".equals(sourceFormat);
        if (normalizedSource.contains("openehr")) {
            return "json".equals(sourceFormat) || fieldText.contains("archetype") || fieldText.contains("template");
        }
        if (normalizedSource.contains("omop")) {
            return fieldText.contains("concept id") || fieldText.contains("person id") || fieldText.contains("omop");
        }
        return false;
    }

    private CandidateScore scoreRegistryEntry(
            DatasetShape shape,
            RegistryEntry entry,
            List<SemanticCdeConversionDTO> conversions
    ) {
        String registryText = entry.title() + " " + entry.registryText() + " " + conversions.stream()
                .flatMap(conversion -> java.util.stream.Stream.of(conversion.label(), conversion.description()))
                .collect(Collectors.joining(" "));
        if (!unitsMatchRegistry(shape.unitValues(), registryText)) {
            return CandidateScore.rejected();
        }
        ShapeScore lexicalScore = scoreDatasetShape(shape, registryText);
        boolean lexicallyPlausible = lexicalScore.matchedFields() >= 3
                || (lexicalScore.matchedFields() >= 2 && lexicalScore.ratio() >= 0.4);
        return new CandidateScore(true, lexicalScore.ratio(), lexicallyPlausible);
    }

    private List<String> datasetSemanticFields(DatasetShape shape) {
        return shape.fields().stream()
                .map(this::normalizeText)
                .filter(field -> !field.isBlank())
                .filter(field -> tokenize(field).stream()
                        .anyMatch(token -> !SEMANTIC_METADATA_TOKENS.contains(token)))
                .limit(24)
                .toList();
    }

    private List<String> registrySemanticDescriptions(
            RegistryEntry entry,
            List<SemanticCdeConversionDTO> conversions
    ) {
        var descriptions = new java.util.LinkedHashSet<String>();
        descriptions.add(entry.title());
        conversions.forEach(conversion -> {
            descriptions.add(conversion.description());
        });

        var matcher = SEMANTIC_LITERAL.matcher(entry.registryText());
        while (matcher.find() && descriptions.size() < 16) {
            descriptions.add(matcher.group(1).replace("\\\"", "\"").replace("\\n", " "));
        }
        return descriptions.stream()
                .filter(description -> description != null && !description.isBlank())
                .map(description -> "Clinical data element: " + description.trim())
                .map(description -> description.length() <= 1_000
                        ? description
                        : description.substring(0, 1_000))
                .limit(16)
                .toList();
    }

    private boolean unitsMatchRegistry(Set<String> unitValues, String registryText) {
        if (unitValues.isEmpty()) {
            return true;
        }
        String compactRegistryText = compactUnit(registryText);
        return unitValues.stream()
                .map(this::compactUnit)
                .filter(unit -> !unit.isBlank())
                .allMatch(compactRegistryText::contains);
    }

    private String compactUnit(String value) {
        return String.valueOf(value).toLowerCase().replaceAll("[^a-z0-9]+", "");
    }

    private ShapeScore scoreDatasetShape(DatasetShape shape, String registryText) {
        Set<String> registryTokens = Set.copyOf(tokenize(registryText));
        int matchedFields = 0;
        for (String field : shape.fields()) {
            List<List<String>> alternatives = fieldTokenAlternatives(field);
            if (!alternatives.isEmpty() && alternatives.stream()
                    .allMatch(options -> options.stream().anyMatch(registryTokens::contains))) {
                matchedFields++;
            }
        }
        double ratio = shape.fields().isEmpty() ? 0 : (double) matchedFields / shape.fields().size();
        return new ShapeScore(matchedFields, ratio);
    }

    private List<List<String>> fieldTokenAlternatives(String field) {
        return tokenize(field).stream()
                .filter(token -> !FIELD_STOP_TOKENS.contains(token))
                .map(token -> {
                    List<String> synonyms = SHAPE_TOKEN_SYNONYMS.getOrDefault(token, List.of());
                    List<String> alternatives = new ArrayList<>();
                    alternatives.add(token);
                    alternatives.addAll(synonyms);
                    return alternatives;
                })
                .toList();
    }

    private List<String> tokenize(String value) {
        String normalized = normalizeText(value);
        if (normalized.isBlank()) {
            return List.of();
        }
        return Pattern.compile("\\s+").splitAsStream(normalized)
                .filter(token -> token.length() > 1)
                .toList();
    }

    private String normalizeText(String value) {
        return String.valueOf(value)
                .toLowerCase()
                .replaceAll("\\.[^.]+$", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private String resolveSemanticCdeResource(String semanticCdePath) {
        String normalizedName = String.valueOf(semanticCdePath).replace("\\", "/");
        if (normalizedName.startsWith("/") || normalizedName.contains("..")) {
            throw new IllegalArgumentException("Invalid Semantic-CDE path.");
        }

        String repositoryUrl = properties.semanticCdeRepositoryUrl();
        if (repositoryUrl == null) {
            throw new IllegalArgumentException("Semantic-CDE file does not exist: " + semanticCdePath);
        }
        return URI.create(repositoryUrl + "/" + normalizedName).toString();
    }

    private List<String> validateMappingIds(List<String> mappingIds) {
        List<String> cleaned = mappingIds.stream()
                .map(String::trim)
                .flatMap(mappingId -> java.util.Arrays.stream(mappingId.split("__")))
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .toList();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("At least one mapping id is required.");
        }
        for (String mappingId : cleaned) {
            if (!SAFE_MAPPING_ID.matcher(mappingId).matches()) {
                throw new IllegalArgumentException("Invalid mapping id: " + mappingId);
            }
        }
        return cleaned;
    }

    private Path semanticCdeOutputBase(Path inputFile) {
        Path datasetsDir = inputFile.toAbsolutePath().normalize().getParent();
        Path nodeDataDir = datasetsDir == null ? null : datasetsDir.getParent();
        if (nodeDataDir == null) {
            throw new IllegalArgumentException("Unable to resolve Semantic-CDE output folder.");
        }
        return nodeDataDir.resolve("mapped_datasets").normalize();
    }

    private Path resolveOutputPath(String requestedFileName, Path outputBase) {
        if (requestedFileName == null || requestedFileName.isBlank()) {
            return outputBase;
        }
        if (!SAFE_OUTPUT_NAME.matcher(requestedFileName).matches()) {
            throw new IllegalArgumentException("Invalid output file name.");
        }

        Path resolved = outputBase.resolve(requestedFileName).normalize();
        if (!resolved.startsWith(outputBase)) {
            throw new IllegalArgumentException("Invalid output path.");
        }
        return resolved;
    }

    private Path outputDirectoryFor(Path outputPath) {
        if (Files.isDirectory(outputPath)) {
            return outputPath;
        }
        Path fileName = outputPath.getFileName();
        if (fileName != null && fileName.toString().contains(".")) {
            return outputPath.getParent();
        }
        return outputPath;
    }

    private Map<String, OutputState> snapshotOutputFiles(Path outputBase) throws IOException {
        if (!Files.exists(outputBase)) {
            return Map.of();
        }
        try (var stream = Files.walk(outputBase)) {
            return stream.filter(Files::isRegularFile)
                    .collect(Collectors.toMap(
                            path -> outputBase.relativize(path).toString(),
                            this::outputState,
                            (first, ignored) -> first,
                            LinkedHashMap::new
                    ));
        }
    }

    private OutputState outputState(Path path) {
        try {
            return new OutputState(Files.getLastModifiedTime(path).toMillis(), Files.size(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> resolveProducedOutputs(
            Path outputPath,
            Map<String, OutputState> previousOutputs,
            Path outputBase
    ) throws IOException {
        if (Files.isRegularFile(outputPath)) {
            return List.of(outputBase.relativize(outputPath).toString());
        }
        if (!Files.isDirectory(outputBase)) {
            return List.of();
        }

        try (var stream = Files.walk(outputBase)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = outputBase.relativize(path).toString();
                        return !outputState(path).equals(previousOutputs.get(fileName));
                    })
                    .map(path -> outputBase.relativize(path).toString())
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private record DatasetShape(String fileName, String sourceFormat, List<String> fields, Set<String> unitValues) {
    }

    private record RegistryLink(String title, String semanticCdePath) {
    }

    private record RegistryEntry(
            String title,
            String semanticCdePath,
            String registryText,
            List<SemanticCdeConversionDTO> conversions,
            Map<String, MappingArtifact> mappingArtifacts
    ) {
    }

    private record MappingArtifact(String uri, String notation) {
    }

    private record ConversionLabel(String source, String target) {
    }

    private record ShapeScore(int matchedFields, double ratio) {
    }

    private record CandidateScore(boolean accepted, double score, boolean lexicallyPlausible) {
        private static CandidateScore rejected() {
            return new CandidateScore(false, 0.0, false);
        }
    }

    private record ScoredRegistryMatch(SemanticCdeDatasetMatchDTO match, double score) {
    }

    private record OutputState(long modifiedAtMillis, long size) {
    }
}
