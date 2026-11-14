package org.taniwha.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Durable, shared release history for one Java node. Lock and commit precede disclosure. */
@Component
public class DiscoveryQueryLedger {
    public static final String BLOCKED = "Statistics withheld by disclosure controls.";
    private static final Logger logger = LoggerFactory.getLogger(DiscoveryQueryLedger.class);
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path datasets, directory, registryFile;
    private final boolean enabled;

    public DiscoveryQueryLedger(String basePath) throws IOException {
        this(basePath, true);
    }

    @Autowired
    public DiscoveryQueryLedger(@Value("${app.path}") String basePath,
                                @Value("${discovery.history.enabled:true}") boolean enabled) throws IOException {
        this.enabled = enabled;
        Path base = Paths.get(basePath).toAbsolutePath().normalize();
        datasets = base.resolve("datasets"); directory = base.resolve("discovery-privacy");
        registryFile = directory.resolve("registry.json");
        if (!enabled) {
            logger.warn("Discovery query history is disabled. Use only isolated nodes with synthetic data.");
            return;
        }
        Files.createDirectories(datasets); Files.createDirectories(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(directory,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        locked(() -> {
            if (!Files.exists(registryFile)) {
                if (Files.exists(directory.resolve("initialised"))) throw new DiscoveryDisclosureBlockedException();
                write(registryFile, new DiscoveryLedgerRegistry(new HashMap<>(), new HashSet<>()));
                Files.writeString(directory.resolve("initialised"), "1", StandardOpenOption.CREATE_NEW);
            } else readRegistry();
            return null;
        });
    }

    public DiscoveryDatasetVersion begin(Path path) {
        if (!enabled) return null;
        try {
            Path real = checkedPath(path);
            if (!supportedDataset(real.getFileName().toString())) throw new DiscoveryDisclosureBlockedException();
            String key = pathKey(real), content = digest(real);
            return locked(() -> {
                DiscoveryLedgerRegistry registry = readRegistry();
                String previous = registry.paths().put(key, content);
                if (!content.equals(previous)) {
                    write(registryFile, registry);
                    logger.info("Discovery dataset version recorded: pathId={} previous={} current={}", key, previous, content);
                }
                return new DiscoveryDatasetVersion(real, key, content);
            });
        } catch (IOException e) { throw new DiscoveryDisclosureBlockedException(); }
    }

    public <T> T release(DiscoveryDatasetVersion version, int rows, BitSet selected, Function<CohortPartition, T> action) {
        // Do not distinguish an absent cohort from a protected one-to-five-record cohort.
        if (selected.cardinality() < org.taniwha.util.AggregateDisclosure.MIN_COUNT) throw new DiscoveryDisclosureBlockedException();
        if (version == null) {
            if (enabled) throw new DiscoveryDisclosureBlockedException();
            return action.apply(checkCohort(new int[rows], selected));
        }
        try {
            return locked(() -> {
                DiscoveryLedgerRegistry registry = readRegistry();
                if (!Objects.equals(registry.paths().get(version.pathKey()), version.contentKey())
                        || !digest(version.path()).equals(version.contentKey())) throw new DiscoveryDisclosureBlockedException();
                Path stateFile = directory.resolve(version.contentKey() + ".json");
                int[] labels;
                if (Files.exists(stateFile)) {
                    DiscoveryLedgerState state = mapper.readValue(stateFile.toFile(), DiscoveryLedgerState.class);
                    if (state.rows() != rows) throw new DiscoveryDisclosureBlockedException();
                    labels = decode(state);
                } else {
                    if (registry.initialised().contains(version.contentKey())) throw new DiscoveryDisclosureBlockedException();
                    labels = new int[rows];
                }
                CohortPartition partition = checkCohort(labels, selected);
                T response = action.apply(partition);
                // Detect in-place changes during calculation, before writing or returning any response.
                if (!digest(version.path()).equals(version.contentKey())) throw new DiscoveryDisclosureBlockedException();
                write(stateFile, new DiscoveryLedgerState(rows, partition.labels()));
                registry.initialised().add(version.contentKey()); write(registryFile, registry);
                return response;
            });
        } catch (IOException | IllegalArgumentException e) { throw new DiscoveryDisclosureBlockedException(); }
    }

    private CohortPartition checkCohort(int[] labels, BitSet selected) {
        CohortPartition partition = new CohortPartition(labels);
        if (!partition.allow(List.of(selected))) throw new DiscoveryDisclosureBlockedException();
        return partition;
    }

    private int[] decode(DiscoveryLedgerState state) {
        if (state.labels() == null || state.labels().length != state.rows() || state.rows() < 0) throw new DiscoveryDisclosureBlockedException();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int label : state.labels()) {
            if (label < 0) throw new DiscoveryDisclosureBlockedException();
            counts.merge(label, 1, Integer::sum);
        }
        if (counts.values().stream().anyMatch(count -> count < org.taniwha.util.AggregateDisclosure.MIN_COUNT)) throw new DiscoveryDisclosureBlockedException();
        return state.labels();
    }

    private DiscoveryLedgerRegistry readRegistry() throws IOException {
        DiscoveryLedgerRegistry value = mapper.readValue(registryFile.toFile(), DiscoveryLedgerRegistry.class);
        if (value.paths() == null || value.initialised() == null) throw new DiscoveryDisclosureBlockedException();
        return value;
    }
    private Path checkedPath(Path path) throws IOException {
        Path real = path.toRealPath();
        if (!real.startsWith(datasets.toRealPath()) || !Files.isRegularFile(real)) throw new DiscoveryDisclosureBlockedException();
        return real;
    }
    private static boolean supportedDataset(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".csv") || lower.endsWith(".xlsx");
    }
    private String pathKey(Path path) {
        return hash(path.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private String digest(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private void write(Path destination, Object value) throws IOException {
        Path temporary = Files.createTempFile(directory, "commit-", ".tmp");
        try {
            byte[] bytes = mapper.writeValueAsBytes(value);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private <T> T locked(LedgerOperation<T> action) throws IOException {
        synchronized (LOCKS.computeIfAbsent(directory, ignored -> new Object())) {
            try (FileChannel channel = FileChannel.open(directory.resolve("ledger.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) { return action.run(); }
        }
    }
}
