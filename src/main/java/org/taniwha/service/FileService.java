package org.taniwha.service;

import lombok.Getter;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.model.Dataset;
import org.taniwha.model.Distribution;
import org.taniwha.model.NodeMetadata;
import org.taniwha.security.FileFilter;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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

    public FileService(FileFilter fileFilter, @Value("${app.path}") String basePath) {
        this.fileFilter = fileFilter;
        this.datasetsFolder = basePath + "/datasets";
        this.mappedDatasetsFolder = this.datasetsFolder;
        this.fhirMappingsFolder = basePath + "/fhir_mappings";
        this.elementsFolder = basePath + "/dataset_elements";
        this.metadataFolder = basePath + "/dataset_metadata";
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

            // parse it with Jena as TTL
            Model model = ModelFactory.createDefaultModel();
            model.read(new StringReader(rdfContent), null, "TTL");
            NodeMetadata nm = new NodeMetadata();
            nm.setContext("https://www.w3.org/ns/dcat.jsonld");

            // find all DCAT datasets
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

        // multi-valued fields
        ds.setKeyword(getStringObjects(dsRes, "http://www.w3.org/ns/dcat#keyword"));
        ds.setTheme(getResourceURIs(dsRes, "http://www.w3.org/ns/dcat#theme"));
        ds.setLanguage(getResourceURIs(dsRes, "http://purl.org/dc/terms/language"));

        // publisher (like a foaf:Organization)
        Resource pubRes = getResourceObject(dsRes, "http://purl.org/dc/terms/publisher");
        if (pubRes != null) {
            String pubName = getStringValue(pubRes, "http://xmlns.com/foaf/0.1/name");
            ds.setPublisher(pubName != null ? pubName : pubRes.getURI());
        }

        // contact point
        Resource cpRes = getResourceObject(dsRes, "http://www.w3.org/ns/dcat#contactPoint");
        if (cpRes != null) {
            ds.setContactPoint(cpRes.getURI());
        }

        // spatial coverage
        Resource spatialRes = getResourceObject(dsRes, "http://purl.org/dc/terms/spatial");
        if (spatialRes != null) {
            // e.g. geometry
            String geometry = getStringValue(spatialRes, "http://www.w3.org/ns/locn#geometry");
            ds.setSpatial(geometry != null ? geometry : spatialRes.getURI());
        }

        // temporal coverage
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

        // distributions
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
            String sanitizedFileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
            sanitizedFileName = sanitizedFileName.replaceAll("(?i)\\.(csv|xlsx)$", "");
            String finalFileName = sanitizedFileName + "_elements.csv";
            String filePath = Paths.get(elementsFolder, finalFileName).toString();
            Files.write(Paths.get(filePath), csvData.getBytes());
            logger.info("Saved dataset elements to {}", filePath);
            return filePath;
        } catch (IOException e) {
            logger.error("Error saving dataset elements for file: {}", fileName, e);
            throw new RuntimeException("Error saving dataset elements for file: " + fileName, e);
        }
    }

    private List<String> listFilesInFolder(String folderPath) {
        try {
            return Files.list(Paths.get(folderPath))
                    .filter(Files::isRegularFile)
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
        if (st != null && st.getObject().isLiteral())
            return st.getObject().asLiteral().getString();
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
        if (st != null && st.getObject().isResource()) {
            return st.getObject().asResource();
        }
        return null;
    }

    private String getResourceObjectURI(Resource subject, String propertyUri) {
        Resource r = getResourceObject(subject, propertyUri);
        return (r != null) ? r.getURI() : null;
    }

}