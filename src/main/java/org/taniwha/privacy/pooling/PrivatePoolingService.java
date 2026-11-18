package org.taniwha.privacy.pooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.dto.FileFilters;
import org.taniwha.service.DataProcessingService;
import org.taniwha.service.FileService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class PrivatePoolingService {
    private static final double HISTORICAL_ALLOWANCE = 4 * Math.log(2);
    private final Path directory;
    private final Path datasets;
    private final FileService files;
    private final DataProcessingService rows;
    private final PrivatePoolingMetadata metadata;
    private final ObjectMapper mapper;
    private final double maxDatasetEpsilon;
    private final PrivateHistogramProfiler profiler;

    public PrivatePoolingService(@Value("${app.path}") String base, FileService files,
                                 DataProcessingService rows, ObjectMapper mapper, PrivatePoolingMetadata metadata,
                                 @Value("${discovery.approximate-pooling.max-dataset-epsilon:2.772588722239781}") double maxDatasetEpsilon) {
        if (!Double.isFinite(maxDatasetEpsilon) || maxDatasetEpsilon <= 0) {
            throw new IllegalArgumentException("Private pooling budget must be finite and positive");
        }
        Path root = Path.of(base).toAbsolutePath().normalize();
        directory = root.resolve("discovery-privacy").resolve("approximate-pooling");
        datasets = root.resolve("datasets");
        this.files = files;
        this.rows = rows;
        this.profiler = new PrivateHistogramProfiler(rows, new SecureRandom());
        this.metadata = metadata;
        this.mapper = mapper;
        this.maxDatasetEpsilon = maxDatasetEpsilon;
    }

    public synchronized Optional<PrivatePoolingRelease> release(FileFilters request) throws IOException {
        if (request == null || !PrivatePoolingPolicy.validName(request.getFileName())) {
            return Optional.empty();
        }
        boolean whole = request.getFilters()==null || request.getFilters().isEmpty();
        Path path = Path.of(files.getDatasetFilePath(request.getFileName())).toRealPath();
        if (!path.startsWith(datasets.toRealPath()) || !Files.isRegularFile(path)) return Optional.empty();
        if (!Files.exists(directory)) {
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectories(directory);
            }
        }
        try (var lock = FileChannel.open(directory.resolve("release.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = lock.lock()) {
            String hash = digest(path);
            Path state = directory.resolve(hash + ".json");
            Path published = directory.resolve(hash + ".published");
            boolean alreadyPublished = Files.exists(published);
            PrivatePoolingSnapshot snapshot;
            if (Files.exists(state)) {
                snapshot = mapper.readValue(state.toFile(), PrivatePoolingSnapshot.class);
                snapshot.policy().validate();
                if (!hash.equals(snapshot.policy().sha256())) return Optional.empty();
                if (alreadyPublished) {
                    double recorded = Double.parseDouble(Files.readString(published).strip());
                    if (!Double.isFinite(recorded) || recorded > snapshotEpsilon(snapshot) + 1e-9)
                        throw new PrivatePoolingUnavailableException("saved-release-accounting-mismatch",
                                "The saved release and its durable privacy accounting disagree. Node review is required.");
                }
                if (snapshot.metadataVersion() < 2) {
                    Path input = immutableInput(path, hash, request.getFileName());
                    try {
                        if (snapshot.metadataVersion()<1)
                            snapshot = PublishedHistogramRestriction.apply(snapshot, metadata.domains(input, request.getFileName()));
                        snapshot = new PrivatePoolingSnapshot(snapshot.policy(),snapshot.releases(),2,snapshot.allocatedAllowance(),
                                profiler.coversDataset(input,snapshot.policy()));
                        store(state, snapshot);
                    } finally { Files.deleteIfExists(input); }
                }
            } else {
                if (alreadyPublished) {
                    throw new PrivatePoolingUnavailableException("saved-release-unavailable",
                            "The protected release was already published but its saved summary is missing. Node review is required.");
                }
                if (whole) return Optional.empty();
                // Each uploaded dataset version has its own allowance. Releases for
                // other datasets remain recorded, but do not block this dataset.
                snapshot = calculateVersion(path, hash, request);
                if (snapshot == null) return Optional.empty();
                store(state, snapshot);
            }
            String group = whole ? null : snapshot.policy().groupFor(request.getFilters(), rows::matchesDiscoveryFilters);
            if (!whole && group == null) return Optional.empty();
            double spent = snapshotEpsilon(snapshot);
            if (spent > maxDatasetEpsilon + 1e-9) throw new PrivatePoolingUnavailableException("saved-release-exceeds-allowance",
                    "This dataset has already used a larger protected-release allowance than this node currently permits.");
            // Older snapshots did not record their configured cap. Do not top
            // them up merely because their sampler rounded below the old default.
            double previousLimit = snapshot.allocatedAllowance() == null ? Math.max(spent, HISTORICAL_ALLOWANCE) : snapshot.allocatedAllowance();
            // A larger operator-configured allowance authorizes one additional
            // measurement. Previously published charges remain part of its cost.
            if (maxDatasetEpsilon > previousLimit + 1e-9) {
                Path input = immutableInput(path, hash, request.getFileName());
                try {
                    var domains = metadata.domains(input, request.getFileName());
                    var old = snapshot.policy();
                    domains.remove(old.groupBy());
                    double available = maxDatasetEpsilon - spent;
                    double minimum = domains.size() * Math.log(1024.0 / 1023);
                    if (!domains.isEmpty() && domains.size() <= 256 && available >= minimum) {
                        var policy = new PrivatePoolingPolicy(request.getFileName(), hash, old.groupBy(), old.groupType(), old.groups(), domains);
                        policy.validate();
                        snapshot = profiler.calculate(input, policy, available, spent, maxDatasetEpsilon);
                        store(state, snapshot);
                    }
                } finally { Files.deleteIfExists(input); }
            }
            if (!alreadyPublished) {
                double epsilon = snapshotEpsilon(snapshot);
                try (var marker = FileChannel.open(published, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    var value = ByteBuffer.wrap(Double.toString(epsilon).getBytes(StandardCharsets.UTF_8));
                    while (value.hasRemaining()) marker.write(value);
                    marker.force(true);
                }
                // Persist the directory entries before a release can leave the node.
                if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    try (var entries = FileChannel.open(directory, StandardOpenOption.READ)) {
                        entries.force(true);
                    }
                }
            } else {
                double recorded = Double.parseDouble(Files.readString(published).strip());
                double current = snapshotEpsilon(snapshot);
                if (!Double.isFinite(recorded) || recorded > current + 1e-9)
                    throw new PrivatePoolingUnavailableException("saved-release-accounting-mismatch",
                            "The saved release and its durable privacy accounting disagree. Node review is required.");
                if (current > recorded + 1e-9) writeCharge(published, current);
            }
            return Optional.ofNullable(whole ? WholeDatasetRelease.from(snapshot) : snapshot.releases().get(group));
        }
    }

    private static double snapshotEpsilon(PrivatePoolingSnapshot snapshot) throws IOException {
        var charges = snapshot.releases().values().stream().mapToDouble(PrivatePoolingRelease::rowEpsilon)
                .distinct().toArray();
        if (charges.length != 1 || !Double.isFinite(charges[0]) || charges[0] <= 0) {
            throw new IOException("Invalid privacy charge in the saved release");
        }
        return charges[0];
    }

    private PrivatePoolingSnapshot calculateVersion(Path path, String hash, FileFilters request) throws IOException {
        String fileName = request.getFileName();
        Path input = immutableInput(path, hash, fileName);
        try {
            var policy = metadata.policy(input, fileName, hash, request.getFilters());
            if (policy == null || policy.groupFor(request.getFilters(), rows::matchesDiscoveryFilters) == null) return null;
            return profiler.calculate(input, policy, maxDatasetEpsilon, 0, maxDatasetEpsilon);
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private Path immutableInput(Path path, String hash, String fileName) throws IOException {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) throw new IOException("Unsupported dataset format");
        Path input = Files.createTempFile(directory, "input-", fileName.substring(dot));
        try {
            Files.copy(path, input, StandardCopyOption.REPLACE_EXISTING);
            if (!hash.equals(digest(input))) throw new IOException("Dataset changed during protected profiling");
            return input;
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(input);
            throw failure;
        }
    }

    private void writeCharge(Path path, double epsilon) throws IOException {
        Path temporary = Files.createTempFile(directory, "charge-", ".tmp");
        try {
            Files.writeString(temporary, Double.toString(epsilon));
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                try (var entries = FileChannel.open(directory, StandardOpenOption.READ)) { entries.force(true); }
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private void store(Path path, PrivatePoolingSnapshot snapshot) throws IOException {
        Path temporary = Files.createTempFile(directory, "release-", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), snapshot);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String digest(Path path) throws IOException {
        try (var input = new DigestInputStream(Files.newInputStream(path), MessageDigest.getInstance("SHA-256"))) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(input.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
