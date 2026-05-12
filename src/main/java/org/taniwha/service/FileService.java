package org.taniwha.service;

import lombok.Getter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.rdf.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.dto.FileInfoDto;
import org.taniwha.model.DatasetDownloadPolicyManifest;
import org.taniwha.model.FileCategory;
import org.taniwha.model.FairDataPointPublishedManifest;
import org.taniwha.model.MetadataDocument;
import org.taniwha.model.NodeMetadata;
import org.taniwha.model.SharedDatasetFile;
import org.taniwha.security.AllowedExtensions;
import org.taniwha.security.FileFilter;
import org.taniwha.util.DCatUtil;
import org.taniwha.util.FairDataPointMetadataUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class FileService {

    private static final Logger logger = LoggerFactory.getLogger(FileService.class);
    private static final String FDP_MANIFEST_FILE_NAME = "published-manifest.json";
    private static final String DATASET_DOWNLOAD_POLICY_FILE_NAME = "dataset-download-policy.json";
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");

    private final FileFilter fileFilter;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> downloadableDatasetSelectors;

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
    private final Path fairDataPointDir;
    private final Path fairDataPointManifestPath;
    private final Path datasetDownloadPolicyPath;
    private final Path sharedDatasetsDir;

    public FileService(FileFilter fileFilter, String basePath) {
        this(fileFilter, basePath, "");
    }

    @Autowired
    public FileService(FileFilter fileFilter,
                       @Value("${app.path}") String basePath,
                       @Value("${datasets.downloadable:}") String downloadableDatasets) {
        this.fileFilter = fileFilter;
        this.downloadableDatasetSelectors = parseDownloadableDatasetSelectors(downloadableDatasets);

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
        this.fairDataPointDir = base.resolve("fairdatapoint").normalize();
        this.fairDataPointManifestPath = fairDataPointDir.resolve(FDP_MANIFEST_FILE_NAME).normalize();
        this.datasetDownloadPolicyPath = fairDataPointDir.resolve(DATASET_DOWNLOAD_POLICY_FILE_NAME).normalize();
        this.sharedDatasetsDir = fairDataPointDir.resolve("datasets").normalize();
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
        List<String> files = new ArrayList<>(listMetadataFiles());
        files.sort(String.CASE_INSENSITIVE_ORDER);

        if (files.isEmpty()) {
            throw new IllegalStateException("No metadata files found in " + metadataFolder);
        }

        String firstFileName = files.stream()
                .filter(this::isRdfMetadataFile)
                .filter(fileName -> !FairDataPointMetadataUtil.MANAGED_METADATA_FILE_NAME.equals(fileName))
                .findFirst()
                .orElseGet(() -> files.stream()
                        .filter(this::isRdfMetadataFile)
                        .findFirst()
                        .orElse(files.get(0)));
        Path metaPath = metadataDir.resolve(firstFileName).normalize();

        if (!metaPath.startsWith(metadataDir)) {
            throw new IllegalArgumentException("Invalid metadata path");
        }

        return metaPath;
    }

    private boolean isRdfMetadataFile(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".ttl");
    }

    public Path resolveDatasetFilePath(String fileName) {
        return resolveExistingFilePath(FileCategory.DATASETS, fileName);
    }

    public boolean isDatasetDownloadAllowed(String fileName) {
        try {
            return resolveSharedDatasetFilePath(fileName) != null;
        } catch (Exception e) {
            logger.warn("Failed to evaluate dataset download policy for {}", fileName, e);
            return false;
        }
    }

    public String datasetDownloadBlockedMessage(String fileName) {
        return "This dataset has been configured to not leave the server. Download is disabled for " + fileName + ".";
    }

    public String logicalDatasetIdFor(String fileName) {
        return logicalDatasetId(fileName);
    }

    public synchronized List<String> setDatasetFamilyDownloadable(String fileName, boolean downloadable) {
        String logicalId = logicalDatasetId(fileName);
        if (logicalId.isBlank()) {
            throw new IllegalArgumentException("Invalid dataset name");
        }

        DatasetDownloadPolicyManifest manifest = ensureDatasetDownloadPolicyFromConfig();
        Set<String> seededSelectors = new LinkedHashSet<>(manifest == null || manifest.seededSelectors() == null
                ? Collections.emptyList()
                : manifest.seededSelectors());
        List<SharedDatasetFile> files = new ArrayList<>(manifest == null || manifest.files() == null
                ? List.of()
                : manifest.files());

        List<String> datasetFiles = new ArrayList<>(listDatasetFiles());
        datasetFiles.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> familyFiles = datasetFiles.stream()
                .filter(candidate -> logicalId.equals(logicalDatasetId(candidate)))
                .toList();

        if (downloadable) {
            for (String familyFile : familyFiles) {
                String fingerprint = datasetFingerprint(resolveDatasetFilePath(familyFile));
                if (files.stream().noneMatch(file -> fingerprint.equals(file.fingerprint()))) {
                    files.add(new SharedDatasetFile(fingerprint, logicalId, familyFile));
                }
            }
            seededSelectors.add(logicalId);
        } else {
            files.removeIf(file -> logicalId.equals(file.groupId()));
        }

        writeDatasetDownloadPolicyManifest(new DatasetDownloadPolicyManifest(
                List.copyOf(seededSelectors),
                List.copyOf(files)
        ));

        if (!downloadable) {
            deleteSharedDatasetGroup(logicalId);
        }

        return familyFiles;
    }

    public Path resolveSharedDatasetFilePath(String fileName) {
        try {
            DatasetDownloadPolicyManifest manifest = ensureDatasetDownloadPolicyFromConfig();
            if (manifest == null || manifest.files() == null || manifest.files().isEmpty()) {
                return null;
            }

            Path sourcePath = resolveDatasetFilePath(fileName);
            String fingerprint = datasetFingerprint(sourcePath);
            SharedFileMatch match = findSharedFileMatch(manifest, fingerprint);
            if (match == null) {
                return null;
            }

            Path groupDir = sharedDatasetsDir.resolve(match.groupId()).normalize();
            Path sharedPath = groupDir.resolve(fileName).normalize();
            if (!sharedPath.startsWith(groupDir)) {
                throw new IllegalArgumentException("Invalid shared dataset path");
            }

            Files.createDirectories(groupDir);
            syncSharedCopy(sourcePath, sharedPath);

            if (!fileName.equals(match.fileName())) {
                updateSharedManifestEntry(fingerprint, match.groupId(), fileName);
                Path oldPath = groupDir.resolve(match.fileName()).normalize();
                if (!oldPath.equals(sharedPath)) {
                    Files.deleteIfExists(oldPath);
                }
            }

            return sharedPath;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to resolve shared dataset file path for " + fileName, e);
        }
    }

    public void registerDerivedDatasetShareability(String sourceFileName, String derivedFileName) {
        DatasetDownloadPolicyManifest manifest = ensureDatasetDownloadPolicyFromConfig();
        if (manifest == null || manifest.files() == null || manifest.files().isEmpty()) {
            return;
        }

        Path sourcePath = resolveDatasetFilePath(sourceFileName);
        SharedFileMatch sourceMatch = findSharedFileMatch(manifest, datasetFingerprint(sourcePath));
        if (sourceMatch == null) {
            return;
        }

        Path derivedPath = resolveDatasetFilePath(derivedFileName);
        String derivedFingerprint = datasetFingerprint(derivedPath);
        if (findSharedFileMatch(manifest, derivedFingerprint) != null) {
            return;
        }

        List<SharedDatasetFile> files = new ArrayList<>(manifest.files());
        files.add(new SharedDatasetFile(derivedFingerprint, sourceMatch.groupId(), derivedFileName));
        writeDatasetDownloadPolicyManifest(new DatasetDownloadPolicyManifest(
                manifest.seededSelectors() == null ? List.of() : manifest.seededSelectors(),
                List.copyOf(files)
        ));
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

    public void writeFairDataPointPublishedManifest(FairDataPointPublishedManifest manifest) {
        try {
            Files.createDirectories(fairDataPointDir);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(fairDataPointManifestPath.toFile(), manifest);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write FAIR Data Point published manifest", e);
        }
    }

    public FairDataPointPublishedManifest readFairDataPointPublishedManifest() {
        try {
            if (!Files.exists(fairDataPointManifestPath) || !Files.isRegularFile(fairDataPointManifestPath)) {
                return null;
            }
            return objectMapper.readValue(fairDataPointManifestPath.toFile(), FairDataPointPublishedManifest.class);
        } catch (IOException e) {
            logger.warn("Failed to read FAIR Data Point published manifest", e);
            return null;
        }
    }

    public void deleteFairDataPointPublishedManifest() {
        try {
            Files.deleteIfExists(fairDataPointManifestPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete FAIR Data Point published manifest", e);
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

    private Set<String> parseDownloadableDatasetSelectors(String raw) {
        Set<String> ids = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }

        for (String token : raw.split(",")) {
            String normalized = normalizedSelector(token);
            if (!normalized.isBlank()) {
                ids.add(normalized);
            }
        }
        return ids;
    }

    private String logicalDatasetId(String fileNameOrId) {
        String value = fileNameOrId == null ? "" : fileNameOrId.trim();
        int lastSlash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            value = value.substring(lastSlash + 1);
        }

        int lastDot = value.lastIndexOf('.');
        if (lastDot > 0) {
            value = value.substring(0, lastDot);
        }

        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("parsed_") && value.length() > 7) {
            value = value.substring(7);
        } else if (lower.startsWith("parsed-") && value.length() > 7) {
            value = value.substring(7);
        }

        value = NON_SLUG.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("-");
        value = value.replaceAll("^-+|-+$", "");
        return value;
    }

    private String normalizedSelector(String token) {
        return logicalDatasetId(token);
    }

    private synchronized DatasetDownloadPolicyManifest ensureDatasetDownloadPolicyFromConfig() {
        DatasetDownloadPolicyManifest manifest = readDatasetDownloadPolicyManifest();
        Set<String> seededSelectors = new LinkedHashSet<>(manifest == null || manifest.seededSelectors() == null
                ? Collections.emptyList()
                : manifest.seededSelectors());
        List<SharedDatasetFile> files = new ArrayList<>(manifest == null || manifest.files() == null
                ? List.of()
                : manifest.files());

        boolean changed = false;
        List<String> datasetFiles = new ArrayList<>(listDatasetFiles());
        datasetFiles.sort(String.CASE_INSENSITIVE_ORDER);

        for (String selector : downloadableDatasetSelectors) {
            if (seededSelectors.contains(selector)) {
                continue;
            }

            String groupId = selector;
            List<String> matchingFiles = datasetFiles.stream()
                    .filter(fileName -> selector.equals(logicalDatasetId(fileName)))
                    .toList();

            for (String fileName : matchingFiles) {
                try {
                    String fingerprint = datasetFingerprint(resolveDatasetFilePath(fileName));
                    if (files.stream().noneMatch(file -> fingerprint.equals(file.fingerprint()))) {
                        files.add(new SharedDatasetFile(fingerprint, groupId, fileName));
                        changed = true;
                    }
                } catch (Exception e) {
                    logger.warn("Failed to seed dataset sharing policy for {}", fileName, e);
                }
            }

            seededSelectors.add(selector);
            changed = true;
        }

        DatasetDownloadPolicyManifest resolved = new DatasetDownloadPolicyManifest(
                List.copyOf(seededSelectors),
                List.copyOf(files)
        );
        if (changed) {
            writeDatasetDownloadPolicyManifest(resolved);
        }
        return resolved;
    }

    private DatasetDownloadPolicyManifest readDatasetDownloadPolicyManifest() {
        try {
            if (!Files.exists(datasetDownloadPolicyPath) || !Files.isRegularFile(datasetDownloadPolicyPath)) {
                return null;
            }
            return objectMapper.readValue(datasetDownloadPolicyPath.toFile(), DatasetDownloadPolicyManifest.class);
        } catch (IOException e) {
            logger.warn("Failed to read dataset download policy manifest", e);
            return null;
        }
    }

    private void writeDatasetDownloadPolicyManifest(DatasetDownloadPolicyManifest manifest) {
        try {
            Files.createDirectories(fairDataPointDir);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(datasetDownloadPolicyPath.toFile(), manifest);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write dataset download policy manifest", e);
        }
    }

    private String datasetFingerprint(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to hash dataset file: " + path, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private SharedFileMatch findSharedFileMatch(DatasetDownloadPolicyManifest manifest, String fingerprint) {
        if (manifest.files() == null) {
            return null;
        }
        return manifest.files().stream()
                .filter(file -> fingerprint.equals(file.fingerprint()))
                .findFirst()
                .map(file -> new SharedFileMatch(file.groupId(), file.fileName()))
                .orElse(null);
    }

    private void syncSharedCopy(Path sourcePath, Path sharedPath) throws IOException {
        if (Files.exists(sharedPath)) {
            long sourceSize = Files.size(sourcePath);
            long sharedSize = Files.size(sharedPath);
            long sourceModified = Files.getLastModifiedTime(sourcePath).toMillis();
            long sharedModified = Files.getLastModifiedTime(sharedPath).toMillis();
            if (sourceSize == sharedSize && sourceModified == sharedModified) {
                return;
            }
        }

        Files.copy(sourcePath, sharedPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private synchronized void updateSharedManifestEntry(String fingerprint, String groupId, String fileName) {
        DatasetDownloadPolicyManifest manifest = readDatasetDownloadPolicyManifest();
        if (manifest == null || manifest.files() == null) {
            return;
        }

        List<SharedDatasetFile> files = new ArrayList<>(manifest.files());
        boolean changed = false;
        for (int i = 0; i < files.size(); i++) {
            SharedDatasetFile file = files.get(i);
            if (fingerprint.equals(file.fingerprint())) {
                files.set(i, new SharedDatasetFile(fingerprint, groupId, fileName));
                changed = true;
                break;
            }
        }

        if (changed) {
            writeDatasetDownloadPolicyManifest(new DatasetDownloadPolicyManifest(
                    manifest.seededSelectors() == null ? List.of() : manifest.seededSelectors(),
                    List.copyOf(files)
            ));
        }
    }

    private String stripExtension(String fileName) {
        if (fileName == null) {
            return "dataset";
        }
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
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
        String fingerprint = category == FileCategory.DATASETS ? datasetFingerprint(src) : null;

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
            if (category == FileCategory.DATASETS && fingerprint != null) {
                DatasetDownloadPolicyManifest manifest = readDatasetDownloadPolicyManifest();
                SharedFileMatch match = manifest == null ? null : findSharedFileMatch(manifest, fingerprint);
                if (match != null) {
                    Path oldSharedPath = sharedDatasetsDir.resolve(match.groupId()).resolve(match.fileName()).normalize();
                    Path newSharedPath = sharedDatasetsDir.resolve(match.groupId()).resolve(sanitizedTo).normalize();
                    if (Files.exists(oldSharedPath) && !oldSharedPath.equals(newSharedPath)) {
                        Files.createDirectories(newSharedPath.getParent());
                        moveFile(oldSharedPath, newSharedPath);
                    }
                    updateSharedManifestEntry(fingerprint, match.groupId(), sanitizedTo);
                }
            }
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

            String fingerprint = category == FileCategory.DATASETS ? datasetFingerprint(p) : null;
            fileFilter.validate(p);
            Files.delete(p);
            if (category == FileCategory.DATASETS && fingerprint != null) {
                removeSharedDatasetPolicyEntry(fingerprint);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Delete failed", e);
        }
    }

    private synchronized void removeSharedDatasetPolicyEntry(String fingerprint) {
        DatasetDownloadPolicyManifest manifest = readDatasetDownloadPolicyManifest();
        if (manifest == null || manifest.files() == null || manifest.files().isEmpty()) {
            return;
        }

        List<SharedDatasetFile> retained = new ArrayList<>();
        SharedDatasetFile removed = null;
        for (SharedDatasetFile file : manifest.files()) {
            if (removed == null && fingerprint.equals(file.fingerprint())) {
                removed = file;
            } else {
                retained.add(file);
            }
        }

        if (removed == null) {
            return;
        }

        writeDatasetDownloadPolicyManifest(new DatasetDownloadPolicyManifest(
                manifest.seededSelectors() == null ? List.of() : manifest.seededSelectors(),
                List.copyOf(retained)
        ));

        Path sharedPath = sharedDatasetsDir.resolve(removed.groupId()).resolve(removed.fileName()).normalize();
        try {
            Files.deleteIfExists(sharedPath);
            cleanupSharedDatasetGroup(removed.groupId());
        } catch (IOException e) {
            logger.warn("Failed to clean shared dataset copy for fingerprint {}", fingerprint, e);
        }
    }

    private void cleanupSharedDatasetGroup(String groupId) throws IOException {
        Path groupDir = sharedDatasetsDir.resolve(groupId).normalize();
        if (!Files.isDirectory(groupDir)) {
            return;
        }
        try (var stream = Files.list(groupDir)) {
            if (stream.findAny().isEmpty()) {
                Files.deleteIfExists(groupDir);
            }
        }
    }

    private void deleteSharedDatasetGroup(String groupId) {
        Path groupDir = sharedDatasetsDir.resolve(groupId).normalize();
        if (!Files.isDirectory(groupDir)) {
            return;
        }
        try (var stream = Files.list(groupDir)) {
            stream.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            Files.deleteIfExists(groupDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to remove shared dataset group " + groupId, e);
        } catch (UncheckedIOException e) {
            throw e;
        }
    }

    private record SharedFileMatch(String groupId, String fileName) {
    }
}
