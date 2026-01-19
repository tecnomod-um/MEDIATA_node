package org.taniwha.service;

import lombok.Getter;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.dto.FileInfoDto;
import org.taniwha.model.Dataset;
import org.taniwha.model.Distribution;
import org.taniwha.model.FileCategory;
import org.taniwha.model.NodeMetadata;
import org.taniwha.security.FileFilter;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FileService {

    private static final Logger logger = LoggerFactory.getLogger(FileService.class);

    private final FileFilter fileFilter;
    private final String datasetsFolder;
    @Getter
    private final String mappedDatasetsFolder;
    private final String fhirMappingsFolder;
    private final String elementsFolder;
    private final String metadataFolder;
    private final Path datasetsDir;
    private final Path mappedDatasetsDir;
    private final Path fhirMappingsDir;
    private final Path elementsDir;
    private final Path metadataDir;

    public FileService(FileFilter fileFilter, @Value("${app.path}") String basePath) {
        this.fileFilter = fileFilter;

        this.datasetsFolder = basePath + "/datasets";
        this.mappedDatasetsFolder = this.datasetsFolder;
        this.fhirMappingsFolder = basePath + "/fhir_mappings";
        this.elementsFolder = basePath + "/dataset_elements";
        this.metadataFolder = basePath + "/dataset_metadata";

        Path base = Paths.get(basePath).normalize();
        this.datasetsDir = base.resolve("datasets").normalize();
        this.mappedDatasetsDir = this.datasetsDir; // keep your current behavior
        this.fhirMappingsDir = base.resolve("fhir_mappings").normalize();
        this.elementsDir = base.resolve("dataset_elements").normalize();
        this.metadataDir = base.resolve("dataset_metadata").normalize();
    }

    public NodeMetadata parseNodeMetadata() {
        try {
            var files = listMetadataFiles();
            if (files.isEmpty()) {
                logger.warn("No metadata files found in {}", metadataFolder);
                return null;
            }

            String firstFileName = files.get(0);
            Path metaPath = Paths.get(metadataFolder, firstFileName);

            fileFilter.validate(metaPath);

            String rdfContent = Files.readString(metaPath);

            Model model = ModelFactory.createDefaultModel();
            model.read(new StringReader(rdfContent), null, "TTL");

            NodeMetadata nm = new NodeMetadata();
            nm.setContext("https://www.w3.org/ns/dcat.jsonld");

            Resource dcatDataset = model.createResource("http://www.w3.org/ns/dcat#Dataset");
            ResIterator dsIter = model.listResourcesWithProperty(RDF.type, dcatDataset);

            List<Dataset> datasetList = new ArrayList<>();
            while (dsIter.hasNext()) {
                Resource dsRes = dsIter.nextResource();
                Dataset ds = parseDataset(dsRes);
                datasetList.add(ds);
            }
            nm.setDataset(datasetList);

            return nm;
        } catch (IOException e) {
            logger.error("Error reading or parsing metadata file", e);
            return null;
        }
    }

    private Dataset parseDataset(Resource dsRes) {
        Dataset ds = new Dataset();

        ds.setTitle(getStringValue(dsRes, "http://purl.org/dc/terms/title"));
        ds.setDescription(getStringValue(dsRes, "http://purl.org/dc/terms/description"));
        ds.setIdentifier(getStringValue(dsRes, "http://purl.org/dc/terms/identifier"));
        ds.setIssued(getStringValue(dsRes, "http://purl.org/dc/terms/issued"));
        ds.setModified(getStringValue(dsRes, "http://purl.org/dc/terms/modified"));
        ds.setAccrualPeriodicity(getStringValue(dsRes, "http://purl.org/dc/terms/accrualPeriodicity"));

        ds.setKeyword(getStringObjects(dsRes, "http://www.w3.org/ns/dcat#keyword"));
        ds.setTheme(getResourceURIs(dsRes, "http://www.w3.org/ns/dcat#theme"));
        ds.setLanguage(getResourceURIs(dsRes, "http://purl.org/dc/terms/language"));

        Resource pubRes = getResourceObject(dsRes, "http://purl.org/dc/terms/publisher");
        if (pubRes != null) {
            String pubName = getStringValue(pubRes, "http://xmlns.com/foaf/0.1/name");
            ds.setPublisher(pubName != null ? pubName : pubRes.getURI());
        }

        Resource cpRes = getResourceObject(dsRes, "http://www.w3.org/ns/dcat#contactPoint");
        if (cpRes != null) {
            ds.setContactPoint(cpRes.getURI());
        }

        Resource spatialRes = getResourceObject(dsRes, "http://purl.org/dc/terms/spatial");
        if (spatialRes != null) {
            String geometry = getStringValue(spatialRes, "http://www.w3.org/ns/locn#geometry");
            ds.setSpatial(geometry != null ? geometry : spatialRes.getURI());
        }

        Resource temporalRes = getResourceObject(dsRes, "http://purl.org/dc/terms/temporal");
        if (temporalRes != null) {
            String start = getStringValue(temporalRes, "http://www.w3.org/ns/dcat#startDate");
            String end = getStringValue(temporalRes, "http://www.w3.org/ns/dcat#endDate");
            if (start != null || end != null) {
                ds.setTemporal("From " + (start != null ? start : "?") + " to " + (end != null ? end : "?"));
            } else {
                ds.setTemporal(temporalRes.getURI());
            }
        }

        ds.setDistribution(parseDistributions(dsRes));
        return ds;
    }

    private List<Distribution> parseDistributions(Resource dsRes) {
        List<Distribution> distList = new ArrayList<>();
        Property distProp = ResourceFactory.createProperty("http://www.w3.org/ns/dcat#distribution");

        StmtIterator it = dsRes.listProperties(distProp);
        while (it.hasNext()) {
            Statement st = it.nextStatement();
            if (st.getObject().isResource()) {
                Resource distRes = st.getObject().asResource();
                Distribution d = new Distribution();

                d.setTitle(getStringValue(distRes, "http://purl.org/dc/terms/title"));
                d.setDescription(getStringValue(distRes, "http://purl.org/dc/terms/description"));
                d.setFormat(getStringValue(distRes, "http://purl.org/dc/terms/format"));
                d.setLicense(getResourceObjectURI(distRes, "http://purl.org/dc/terms/license"));
                d.setDownloadURL(getResourceObjectURI(distRes, "http://www.w3.org/ns/dcat#downloadURL"));

                distList.add(d);
            }
        }
        return distList.isEmpty() ? null : distList;
    }

    private List<String> getResourceURIs(Resource subject, String propertyUri) {
        List<String> uris = new ArrayList<>();
        Property prop = ResourceFactory.createProperty(propertyUri);
        StmtIterator it = subject.listProperties(prop);
        while (it.hasNext()) {
            Statement st = it.nextStatement();
            if (st.getObject().isResource()) {
                String uri = st.getObject().asResource().getURI();
                uris.add(uri);
            }
        }
        return uris.isEmpty() ? null : uris;
    }

    public List<String> listDatasetFiles() {
        return listFilesInFolder(datasetsFolder);
    }

    public List<String> listMappedDatasetFiles() {
        return listFilesInFolder(mappedDatasetsFolder);
    }

    public List<String> listFhirMappingFiles() {
        return listFilesInFolder(fhirMappingsFolder);
    }

    public List<String> listElementFiles() {
        return listFilesInFolder(elementsFolder);
    }

    public List<String> listMetadataFiles() {
        return listFilesInFolder(metadataFolder);
    }

    public String getDatasetFilePath(String fileName) {
        return Paths.get(datasetsFolder, fileName).toString();
    }

    public String getElementFilePath(String fileName) {
        return Paths.get(elementsFolder, fileName).toString();
    }

    public String getMetadataFilePath(String fileName) {
        return Paths.get(metadataFolder, fileName).toString();
    }

    public String saveDatasetElements(String fileName, String csvData) {
        try {
            String sanitizedFileName = sanitizeFileName(fileName);
            sanitizedFileName = sanitizedFileName.replaceAll("(?i)\\.(csv|xlsx)$", "");
            String finalFileName = sanitizedFileName + "_elements.csv";

            String filePath = Paths.get(elementsFolder, finalFileName).toString();

            Path outPath = Paths.get(filePath).normalize();
            fileFilter.validate(outPath);

            Files.write(outPath, csvData.getBytes(StandardCharsets.UTF_8));
            logger.info("Saved dataset elements to {}", filePath);
            return filePath;
        } catch (IOException e) {
            logger.error("Error saving dataset elements for file: {}", fileName, e);
            throw new RuntimeException("Error saving dataset elements for file: " + fileName, e);
        }
    }

    private List<String> listFilesInFolder(String folderPath) {
        try {
            Path dir = Paths.get(folderPath);
            if (!Files.exists(dir)) return new ArrayList<>();

            return Files.list(dir)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        try {
                            fileFilter.validate(p);
                            return true;
                        } catch (Exception e) {
                            logger.debug("File validation failed for {}: {}", p, e.getMessage());
                            return false;
                        }
                    })
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Failed to list files in folder: {}", folderPath, e);
            return new ArrayList<>();
        }
    }

    private String getStringValue(Resource subject, String propertyUri) {
        Property prop = ResourceFactory.createProperty(propertyUri);
        Statement st = subject.getProperty(prop);
        if (st != null && st.getObject().isLiteral()) return st.getObject().asLiteral().getString();
        return null;
    }

    private List<String> getStringObjects(Resource subject, String propertyUri) {
        List<String> results = new ArrayList<>();
        Property prop = ResourceFactory.createProperty(propertyUri);
        StmtIterator it = subject.listProperties(prop);
        while (it.hasNext()) {
            Statement st = it.nextStatement();
            if (st.getObject().isLiteral()) {
                results.add(st.getObject().asLiteral().getString());
            }
        }
        return results.isEmpty() ? null : results;
    }

    private Resource getResourceObject(Resource subject, String propertyUri) {
        Property prop = ResourceFactory.createProperty(propertyUri);
        Statement st = subject.getProperty(prop);
        if (st != null && st.getObject().isResource()) return st.getObject().asResource();
        return null;
    }

    private String getResourceObjectURI(Resource subject, String propertyUri) {
        Resource r = getResourceObject(subject, propertyUri);
        return (r != null) ? r.getURI() : null;
    }

    private Path dirFor(FileCategory category) {
        return switch (category) {
            case DATASETS -> datasetsDir;
            case MAPPED_DATASETS -> mappedDatasetsDir;
            case FHIR_MAPPINGS -> fhirMappingsDir;
            case DATASET_ELEMENTS -> elementsDir;
            case DATASET_METADATA -> metadataDir;
        };
    }

    public Path resolveExistingFilePath(FileCategory category, String fileName) {
        return resolveSafeExistingFile(category, fileName);
    }
    
    private Path resolveSafePath(FileCategory category, String fileName) {
        String safeName = String.valueOf(fileName).replace("\\", "/");
        if (safeName.contains("/")) {
            throw new IllegalArgumentException("Invalid file name");
        }

        Path dir = dirFor(category);
        Path resolved = dir.resolve(safeName).normalize();

        if (!resolved.startsWith(dir)) {
            throw new IllegalArgumentException("Invalid path");
        }

        return resolved;
    }

    private Path resolveSafeExistingFile(FileCategory category, String fileName) {
        Path p = resolveSafePath(category, fileName);

        fileFilter.validate(p);

        if (!Files.exists(p)) {
            throw new IllegalArgumentException("File does not exist");
        }
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("Not a file");
        }
        return p;
    }

    private String sanitizeFileName(String name) {
        return String.valueOf(name).replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public List<FileInfoDto> listFilesWithInfo(FileCategory category) {
        Path dir = dirFor(category);

        try {
            if (!Files.exists(dir)) return List.of();

            List<FileInfoDto> out = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream
                        .filter(Files::isRegularFile)
                        .forEach(p -> {
                            try {
                                fileFilter.validate(p);
                                BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);

                                long createdMs = (attrs.creationTime() != null)
                                        ? attrs.creationTime().toMillis()
                                        : attrs.lastModifiedTime().toMillis();

                                long modifiedMs = (attrs.lastModifiedTime() != null)
                                        ? attrs.lastModifiedTime().toMillis()
                                        : createdMs;

                                out.add(new FileInfoDto(
                                        p.getFileName().toString(),
                                        attrs.size(),
                                        createdMs,
                                        modifiedMs
                                ));
                            } catch (Exception e) {
                                logger.warn("Skipping file due to validation/read error: {}", p, e);
                            }
                        });
            }

            out.sort(Comparator.comparing(FileInfoDto::getName, String.CASE_INSENSITIVE_ORDER));
            return out;

        } catch (IOException e) {
            logger.error("Failed to list files with info for category={}", category, e);
            return List.of();
        }
    }

    public void renameFile(FileCategory category, String from, String to) {
        Path src = resolveSafeExistingFile(category, from);

        String sanitizedTo = sanitizeFileName(to).trim();
        if (sanitizedTo.isEmpty()) {
            throw new IllegalArgumentException("Invalid destination name");
        }

        if (sanitizedTo.equals(from)) return;

        Path dst = resolveSafePath(category, sanitizedTo);

        try {
            if (Files.exists(dst)) throw new IllegalArgumentException("Destination already exists");

            try {
                Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(src, dst);
            }
        } catch (IOException e) {
            throw new RuntimeException("Rename failed", e);
        }
    }

    public void deleteFile(FileCategory category, String name) {
        Path p = resolveSafePath(category, name);
        try {
            if (!Files.exists(p))
                return;
            fileFilter.validate(p);
            Files.delete(p);
        } catch (IOException e) {
            throw new RuntimeException("Delete failed", e);
        }
    }

    public void cleanFilePlaceholder(FileCategory category, String name) {
        // Clean up placeholder file for the given category and name
        resolveSafeExistingFile(category, name);
    }
}
