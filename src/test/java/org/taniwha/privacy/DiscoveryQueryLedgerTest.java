package org.taniwha.privacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class DiscoveryQueryLedgerTest {
    @TempDir Path base;
    private Path dataset() throws Exception {
        Path path = base.resolve("datasets/cohort.csv"); Files.createDirectories(path.getParent());
        Files.writeString(path, "Age\n" + java.util.stream.IntStream.range(0, 24).mapToObj(i -> String.valueOf(20 + i)).collect(java.util.stream.Collectors.joining("\n")));
        return path;
    }
    private BitSet cohort(int from, int to) { BitSet set = new BitSet(); set.set(from, to); return set; }
    private int release(DiscoveryQueryLedger ledger, Path file, BitSet selected) {
        return ledger.release(ledger.begin(file), 24, selected, partition -> selected.cardinality());
    }
    @Test void blocksOneRecordDifferencesAndSmallComplementsWithoutCallingTheRelease() throws Exception {
        Path file = dataset(); var ledger = new DiscoveryQueryLedger(base.toString());
        assertThat(release(ledger, file, cohort(0, 24))).isEqualTo(24);
        assertThat(release(ledger, file, cohort(0, 12))).isEqualTo(12);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> ledger.release(ledger.begin(file), 24, cohort(0, 11), p -> calls.incrementAndGet())).isInstanceOf(DiscoveryQueryLedger.Blocked.class);
        assertThatThrownBy(() -> release(ledger, file, cohort(0, 23))).isInstanceOf(DiscoveryQueryLedger.Blocked.class);
        assertThat(calls).hasValue(0);
    }
    @Test void checksMultiwayOverlapsAndMakesRefinementAtomic() throws Exception {
        Path file = dataset(); var ledger = new DiscoveryQueryLedger(base.toString());
        release(ledger, file, cohort(0, 12)); release(ledger, file, cohort(6, 18));
        BitSet attack = cohort(0, 6); attack.set(6); attack.or(cohort(12, 17));
        assertThatThrownBy(() -> release(ledger, file, attack)).isInstanceOf(DiscoveryQueryLedger.Blocked.class);
        ledger.release(ledger.begin(file), 24, cohort(0, 24), partition -> {
            assertThat(partition.allow(List.of(cohort(0, 6), cohort(0, 1)))).isFalse();
            assertThat(partition.cells()).allSatisfy(cell -> assertThat(cell.cardinality()).isEqualTo(6));
            return 0;
        });
    }
    @Test void historyIsSharedAcrossUsersRestartsAndIdenticalFileAliases() throws Exception {
        Path file = dataset(); var first = new DiscoveryQueryLedger(base.toString()); release(first, file, cohort(0, 12));
        Path copy = file.resolveSibling("renamed.csv"); Files.copy(file, copy);
        var restarted = new DiscoveryQueryLedger(base.toString());
        assertThatThrownBy(() -> release(restarted, copy, cohort(0, 11))).isInstanceOf(DiscoveryQueryLedger.Blocked.class);
        assertThat(release(restarted, copy, cohort(0, 12))).isEqualTo(12);
    }
    @Test void changedAndUnapprovedDatasetsAreBlockedIncludingNewAliases() throws Exception {
        Path file = dataset(); var ledger = new DiscoveryQueryLedger(base.toString()); release(ledger, file, cohort(0, 24));
        Files.writeString(file, "Age\n900\n");
        assertThatThrownBy(() -> ledger.begin(file)).isInstanceOf(DiscoveryQueryLedger.Blocked.class);
        Path alias = file.resolveSibling("new-name.csv"); Files.copy(file, alias);
        assertThatThrownBy(() -> ledger.begin(alias)).isInstanceOf(DiscoveryQueryLedger.Blocked.class);
    }
    @Test void cannotResetHistoryByDeletingAStateOrRegistryFile() throws Exception {
        Path file = dataset(); var ledger = new DiscoveryQueryLedger(base.toString());
        var version = ledger.begin(file); release(ledger, file, cohort(0, 12));
        Files.delete(base.resolve("discovery-privacy/" + version.contentKey() + ".json"));
        assertThatThrownBy(() -> release(ledger, file, cohort(0, 11))).isInstanceOf(DiscoveryQueryLedger.Blocked.class);
        Files.delete(base.resolve("discovery-privacy/registry.json"));
        assertThatThrownBy(() -> new DiscoveryQueryLedger(base.toString())).isInstanceOf(DiscoveryQueryLedger.Blocked.class);
    }
    @Test void concurrentReleasesCannotBothAdmitIncompatibleQueries() throws Exception {
        Path file = dataset(); var a = new DiscoveryQueryLedger(base.toString()); var b = new DiscoveryQueryLedger(base.toString());
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int n : List.of(11, 12)) futures.add(executor.submit(() -> {
                start.await(); try { release(n == 11 ? a : b, file, cohort(0, n)); return true; }
                catch (DiscoveryQueryLedger.Blocked denied) { return false; }
            }));
            start.countDown(); int accepted = 0; for (Future<Boolean> future : futures) if (future.get(5, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
    @Test void persistedHistoryContainsNoPredicatesOrRecordValues() throws Exception {
        Path file = dataset(); var ledger = new DiscoveryQueryLedger(base.toString()); release(ledger, file, cohort(0, 12));
        try (var files = Files.list(base.resolve("discovery-privacy"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).toList())
                assertThat(Files.readString(path)).doesNotContain("Age", "cohort.csv", "conditions", "featureX");
        }
    }
}
