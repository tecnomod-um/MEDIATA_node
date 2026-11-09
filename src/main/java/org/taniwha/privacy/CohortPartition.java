package org.taniwha.privacy;

import org.taniwha.util.AggregateDisclosure;
import java.util.*;

/** Node-local membership equivalence classes; storage is linear in the dataset size. */
public final class CohortPartition {
    private int[] labels;
    public CohortPartition(int[] labels) { this.labels = labels.clone(); }
    public boolean allow(Collection<BitSet> cohorts) {
        int[] next = labels;
        for (BitSet cohort : cohorts) {
            if (cohort == null || cohort.length() > next.length) return false;
            Map<Long, Integer> counts = new HashMap<>();
            for (int row = 0; row < next.length; row++) counts.merge(2L * next[row] + (cohort.get(row) ? 1 : 0), 1, Integer::sum);
            if (counts.values().stream().anyMatch(count -> count < AggregateDisclosure.MIN_COUNT)) return false;
            Map<Long, Integer> ids = new HashMap<>(); int[] refined = new int[next.length];
            for (int row = 0; row < next.length; row++) {
                long code = 2L * next[row] + (cohort.get(row) ? 1 : 0);
                refined[row] = ids.computeIfAbsent(code, ignored -> ids.size());
            }
            next = refined;
        }
        labels = next;
        return true;
    }
    public int[] labels() { return labels.clone(); }
    public List<BitSet> cells() {
        Map<Integer, BitSet> cells = new HashMap<>();
        for (int row = 0; row < labels.length; row++) cells.computeIfAbsent(labels[row], ignored -> new BitSet()).set(row);
        return new ArrayList<>(cells.values());
    }
}
