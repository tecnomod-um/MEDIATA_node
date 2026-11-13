package org.taniwha.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
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

// Durable, shared release history for one Java node. Lock and commit precede disclosure.
@Component
public class DiscoveryQueryLedger {
    public static final String BLOCKED = "Statistics withheld by disclosure controls. The query history or dataset requires review.";
    public static final String UNREGISTERED = "Statistics withheld by disclosure controls. Ask the site operator to register this dataset for Discovery.";

    public static final class Blocked extends RuntimeException {
        public Blocked() { this(BLOCKED); }
        private Blocked(String message) { super(message); }
    }

    public record Version(Path path, String pathKey, String contentKey) {}
    private record Registry(Map<String, String> paths, Set<String> approved, Set<String> initialised) {}
    private record State(int rows, int[] labels) {}
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path datasets, directory, registryFile;

    @Autowired
    public DiscoveryQueryLedger(@Value("${app.path}") String basePath) throws IOException {
        this(basePath, true);
    }

    static DiscoveryQueryLedger openExisting(String basePath) throws IOException {
        return new DiscoveryQueryLedger(basePath, false);
    }

    private DiscoveryQueryLedger(String basePath, boolean initialise) throws IOException {
        Path base = Paths.get(basePath).toAbsolutePath().normalize();
        datasets = base.resolve("datasets"); directory = base.resolve("discovery-privacy");
        registryFile = directory.resolve("registry.json");
        Files.createDirectories(datasets); Files.createDirectories(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(directory,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        locked(() -> {
            if (!Files.exists(registryFile)) {
                if (!initialise || Files.exists(directory.resolve("initialised"))) throw new Blocked();
                Registry registry = new Registry(new HashMap<>(), new HashSet<>(), new HashSet<>());
                try (var files = Files.list(datasets)) {
                    for (Path path : files.filter(Files::isRegularFile).toList()) {
                        if (!supportedDataset(path.getFileName().toString())) continue;
                        Path real = checkedPath(path);
                        String content = digest(real);
                        registry.paths().put(pathKey(real), content);
                        registry.approved().add(content);
                    }
                }
                write(registryFile, registry);
                Files.writeString(directory.resolve("initialised"), "1", StandardOpenOption.CREATE_NEW);
            } else readRegistry();
            return null;
        });
    }

    void registerDataset(String fileName) throws IOException {
        if (fileName.isBlank() || fileName.contains("/") || fileName.contains("\\") || !supportedDataset(fileName)) {
            throw new IllegalArgumentException("Specify a CSV or XLSX filename from the node's datasets directory.");
        }
        locked(() -> {
            Path real = checkedPath(datasets.resolve(fileName));
            String key = pathKey(real);
            String content = digest(real);
            Registry registry = readRegistry();
            String previous = registry.paths().get(key);
            if (previous != null && !previous.equals(content)) throw new Blocked();
            if (previous != null && registry.approved().contains(content)) return null;
            registry.paths().put(key, content);
            registry.approved().add(content);
            write(registryFile, registry);
            return null;
        });
    }

    public Version begin(Path path) {
        try {
            Path real = checkedPath(path);
            String pathKey = pathKey(real), content = digest(real);
            return locked(() -> {
                Registry registry = readRegistry();
                String old = registry.paths().get(pathKey);
                if (old != null && !old.equals(content)) throw new Blocked();
                if (!registry.approved().contains(content)) throw new Blocked(UNREGISTERED);
                if (old == null) { registry.paths().put(pathKey, content); write(registryFile, registry); }
                return new Version(real, pathKey, content);
            });
        } catch (IOException e) { throw new Blocked(); }
    }

    public <T> T release(Version version, int rows, BitSet selected, Function<CohortPartition, T> action) {
        // Do not distinguish an absent cohort from a protected one-to-five-record cohort.
        if (selected.cardinality() < org.taniwha.util.AggregateDisclosure.MIN_COUNT) throw new Blocked();
        try {
            return locked(() -> {
                Registry registry = readRegistry();
                if (!Objects.equals(registry.paths().get(version.pathKey()), version.contentKey())
                        || !digest(version.path()).equals(version.contentKey())) throw new Blocked();
                Path stateFile = directory.resolve(version.contentKey() + ".json");
                int[] labels;
                if (Files.exists(stateFile)) {
                    State state = mapper.readValue(stateFile.toFile(), State.class);
                    if (state.rows() != rows) throw new Blocked();
                    labels = decode(state);
                } else {
                    if (registry.initialised().contains(version.contentKey())) throw new Blocked();
                    labels = new int[rows];
                }
                CohortPartition partition = new CohortPartition(labels);
                if (!partition.allow(List.of(selected))) throw new Blocked();
                T response = action.apply(partition);
                // Detect in-place changes during calculation, before writing or returning any response.
                if (!digest(version.path()).equals(version.contentKey())) throw new Blocked();
                write(stateFile, new State(rows, partition.labels()));
                registry.initialised().add(version.contentKey()); write(registryFile, registry);
                return response;
            });
        } catch (IOException | IllegalArgumentException e) { throw new Blocked(); }
    }

    private int[] decode(State state) {
        if (state.labels() == null || state.labels().length != state.rows() || state.rows() < 0) throw new Blocked();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int label : state.labels()) {
            if (label < 0) throw new Blocked();
            counts.merge(label, 1, Integer::sum);
        }
        if (counts.values().stream().anyMatch(count -> count < org.taniwha.util.AggregateDisclosure.MIN_COUNT)) throw new Blocked();
        return state.labels();
    }

    private Registry readRegistry() throws IOException {
        Registry value = mapper.readValue(registryFile.toFile(), Registry.class);
        if (value.paths() == null || value.approved() == null || value.initialised() == null) throw new Blocked();
        return value;
    }

    private Path checkedPath(Path path) throws IOException {
        Path real = path.toRealPath();
        if (!real.startsWith(datasets.toRealPath()) || !Files.isRegularFile(real)) throw new Blocked();
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

    private interface IOAction<T> { T run() throws IOException; }
    private <T> T locked(IOAction<T> action) throws IOException {
        synchronized (LOCKS.computeIfAbsent(directory, ignored -> new Object())) {
            try (FileChannel channel = FileChannel.open(directory.resolve("ledger.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) { return action.run(); }
        }
    }
}
