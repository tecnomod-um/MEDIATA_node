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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

@Service
public class PrivatePoolingService {
    private static final int VERSION = 3;
    private final Path directory;
    private final Path datasets;
    private final FileService files;
    private final DataProcessingService rows;
    private final PrivatePoolingMetadata metadata;
    private final ObjectMapper mapper;
    private final double maxDatasetEpsilon;
    private final SecureRandom random = new SecureRandom();

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
        this.metadata = metadata;
        this.mapper = mapper;
        this.maxDatasetEpsilon = maxDatasetEpsilon;
    }

    public synchronized Optional<PrivatePoolingRelease> release(FileFilters request) throws IOException {
        if (request == null || !PrivatePoolingPolicy.validName(request.getFileName()) || request.getFilters() == null) {
            return Optional.empty();
        }
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
            } else {
                if (alreadyPublished) {
                    throw new PrivatePoolingUnavailableException("saved-release-unavailable",
                            "The protected release was already published but its saved summary is missing. Node review is required.");
                }
                // Each uploaded dataset version has its own allowance. Releases for
                // other datasets remain recorded, but do not block this dataset.
                snapshot = calculateVersion(path, hash, request);
                if (snapshot == null) return Optional.empty();
                store(state, snapshot);
            }
            String group = snapshot.policy().groupFor(request.getFilters(), rows::matchesDiscoveryFilters);
            if (group == null) return Optional.empty();
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
            }
            return Optional.ofNullable(snapshot.releases().get(group));
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
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return null;
        String extension = fileName.substring(dot);
        Path input = Files.createTempFile(directory, "input-", extension);
        try {
            Files.copy(path, input, StandardCopyOption.REPLACE_EXISTING);
            if (!hash.equals(digest(input))) return null;
            var policy = metadata.policy(input, fileName, hash, request.getFilters());
            if (policy == null || policy.groupFor(request.getFilters(), rows::matchesDiscoveryFilters) == null) return null;
            return calculate(input, policy);
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private PrivatePoolingSnapshot calculate(Path path, PrivatePoolingPolicy policy) throws IOException {
        var plan = PrivatePoolingNoisePlan.forFeatures(policy.features(), random, maxDatasetEpsilon);
        var firstHeaders = new AtomicReference<List<String>>();
        Map<String, Map<String, long[]>> histograms = new LinkedHashMap<>();
        var groupKeys = new LinkedHashMap<String, String>();
        for (String group : policy.groups()) {
            groupKeys.put(policy.groupKey(group), group);
            var counts = new LinkedHashMap<String, long[]>();
            policy.features().forEach((name, domain) -> counts.put(name, new long[domain.size()]));
            histograms.put(group, counts);
        }
        rows.streamRows(path, headers -> {
            if (firstHeaders.get() != null) {
                if (!firstHeaders.get().equals(headers)) {
                    throw new IllegalArgumentException("Protected summaries require matching worksheet headers");
                }
                return;
            }
            firstHeaders.set(List.copyOf(headers));
            if (!headers.contains(policy.groupBy()) || !headers.containsAll(policy.features().keySet()))
                throw new IllegalArgumentException("Published feature metadata does not match dataset columns");
        }, row -> {
            var counts = histograms.get(groupKeys.get(policy.groupKey(row.get(policy.groupBy()))));
            if (counts != null) policy.features().forEach((name, domain) ->
                    counts.get(name)[domain.indexOf(row.get(name))]++);
        });
        var result = new LinkedHashMap<String, PrivatePoolingRelease>();
        histograms.forEach((group, counts) -> {
            var features = new LinkedHashMap<String, PrivatePoolingHistogram>();
            counts.forEach((name, cells) -> {
                var noise = plan.samplers().get(name);
                features.put(name, new PrivatePoolingHistogram(policy.features().get(name),
                        LongStream.of(cells).map(noise::release).boxed().toList(), noise.variance()));
            });
            result.put(group, new PrivatePoolingRelease(VERSION, group, plan.rowEpsilon(), null, features));
        });
        return new PrivatePoolingSnapshot(policy, result);
    }

    private void store(Path path, PrivatePoolingSnapshot snapshot) throws IOException {
        Path temporary = Files.createTempFile(directory, "release-", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), snapshot);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
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
