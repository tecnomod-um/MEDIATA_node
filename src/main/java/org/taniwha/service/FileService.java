package org.taniwha.service;

import lombok.Getter;
import org.apache.jena.rdf.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.dto.FileInfoDto;
import org.taniwha.model.FileCategory;
import org.taniwha.model.MetadataDocument;
import org.taniwha.model.NodeMetadata;
import org.taniwha.security.AllowedExtensions;
import org.taniwha.security.FileFilter;
import org.taniwha.util.DCatUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        this.mappedDatasetsDir = this.datasetsDir;
        this.fhirMappingsDir = base.resolve("fhir_mappings").normalize();
        this.elementsDir = base.resolve("dataset_elements").normalize();
        this.metadataDir = base.resolve("dataset_metadata").normalize();
    }

    public NodeMetadata parseNodeMetadata() {
        try {
            Path metaPath = getFirstMetadataPath();

            fileFilter.validate(metaPath);

            String rdfContent = Files.readString(metaPath);
            Model model = DCatUtil.readModel(rdfContent, metaPath.getFileName().toString());

            return DCatUtil.parseNodeMetadata(model, metaPath.getFileName().toString());
        } catch (Exception e) {
            logger.error("Error reading or parsing metadata file", e);
            return null;
        }
    }

    public String getRawNodeMetadata() {
        try {
            Path metaPath = getFirstMetadataPath();

            fileFilter.validate(metaPath);

            return Files.readString(metaPath);
        } catch (IOException e) {
            logger.error("Error reading raw metadata file", e);
            return null;
        } catch (Exception e) {
            logger.error("Error resolving raw metadata file", e);
            return null;
        }
    }

    public String getRawNodeMetadataFileName() {
        try {
            return getFirstMetadataPath().getFileName().toString();
        } catch (Exception e) {
            logger.error("Error resolving metadata file name", e);
            return null;
        }
    }

    private Path getFirstMetadataPath() {
        List<String> files = listMetadataFiles();

        if (files.isEmpty()) {
            throw new IllegalStateException("No metadata files found in " + metadataFolder);
        }

        String firstFileName = files.get(0);
        Path metaPath = metadataDir.resolve(firstFileName).normalize();

        if (!metaPath.startsWith(metadataDir)) {
            throw new IllegalArgumentException("Invalid metadata path");
        }

        return metaPath;
    }

    public Path resolveDatasetFilePath(String fileName) {
        return resolveExistingFilePath(FileCategory.DATASETS, fileName);
    }

    public Path resolveElementFilePath(String fileName) {
        return resolveExistingFilePath(FileCategory.DATASET_ELEMENTS, fileName);
    }

    public Path resolveMappedDatasetFilePath(String fileName) {
        return resolveExistingFilePath(FileCategory.MAPPED_DATASETS, fileName);
    }

    public Path resolveMetadataFilePath(String fileName) {
        return resolveExistingFilePath(FileCategory.DATASET_METADATA, fileName);
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

    public List<MetadataDocument> readAllMetadataDocuments() {
        List<String> fileNames = new ArrayList<>(listMetadataFiles());
        fileNames.sort(String.CASE_INSENSITIVE_ORDER);

        List<MetadataDocument> documents = new ArrayList<>();
        for (String fileName : fileNames) {
            Path path = resolveMetadataFilePath(fileName);
            try {
                fileFilter.validate(path);
                documents.add(new MetadataDocument(fileName, Files.readString(path)));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read metadata file: " + fileName, e);
            }
        }

        return documents;
    }

    public MetadataDocument writeMetadataDocument(String fileName, String content) {
        Path path = resolveSafePath(FileCategory.DATASET_METADATA, fileName);
        try {
            Files.createDirectories(metadataDir);
            Files.writeString(
                    path,
                    content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            fileFilter.validate(path);
            return new MetadataDocument(fileName, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write metadata file: " + fileName, e);
        }
    }

    public String getDatasetFilePath(String fileName) {
        return resolveDatasetFilePath(fileName).toString();
    }

    public String getElementFilePath(String fileName) {
        return resolveElementFilePath(fileName).toString();
    }

    public String getMetadataFilePath(String fileName) {
        return resolveMetadataFilePath(fileName).toString();
    }

    public String saveDatasetElements(String fileName, String csvData) {
        try {
            String sanitizedFileName = sanitizeFileName(fileName);
            sanitizedFileName = sanitizedFileName.replaceAll("(?i)\\.(csv|xlsx)$", "");
            String finalFileName = sanitizedFileName + "_elements.csv";

            Path outPath = elementsDir.resolve(finalFileName).normalize();

            if (!outPath.startsWith(elementsDir)) {
                throw new IllegalArgumentException("Invalid path");
            }

            if (!AllowedExtensions.isAllowed("csv")) {
                throw new IllegalArgumentException("Invalid extension");
            }

            Files.writeString(outPath, csvData);

            logger.info("Saved dataset elements to {}", outPath);

            return outPath.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Error saving dataset elements for file: " + fileName, e);
        }
    }

    private List<String> listFilesInFolder(String folderPath) {
        try {
            Path dir = Paths.get(folderPath).normalize();

            if (!Files.exists(dir)) {
                return new ArrayList<>();
            }

            try (var files = Files.list(dir)) {
                return files
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
                        .toList();
            }
        } catch (IOException e) {
            logger.error("Failed to list files in folder: {}", folderPath, e);
            return new ArrayList<>();
        }
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
            if (!Files.exists(dir)) {
                return List.of();
            }

            List<FileInfoDto> out = new ArrayList<>();

            try (var stream = Files.list(dir)) {
                stream
                        .filter(Files::isRegularFile)
                        .forEach(p -> {
                            try {
                                fileFilter.validate(p);

                                BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);

                                long createdMs = attrs.creationTime() != null
                                        ? attrs.creationTime().toMillis()
                                        : attrs.lastModifiedTime().toMillis();

                                long modifiedMs = attrs.lastModifiedTime() != null
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

        if (sanitizedTo.equals(from)) {
            return;
        }

        Path dst = resolveSafePath(category, sanitizedTo);

        try {
            if (Files.exists(dst)) {
                throw new IllegalArgumentException("Destination already exists");
            }

            moveFile(src, dst);
        } catch (IOException e) {
            throw new UncheckedIOException("Rename failed", e);
        }
    }

    private void moveFile(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            logger.debug("Atomic move not supported, falling back to regular move: {}", e.getMessage());
            Files.move(src, dst);
        }
    }

    public void deleteFile(FileCategory category, String name) {
        Path p = resolveSafePath(category, name);

        try {
            if (!Files.exists(p)) {
                return;
            }

            fileFilter.validate(p);
            Files.delete(p);
        } catch (IOException e) {
            throw new UncheckedIOException("Delete failed", e);
        }
    }
}
