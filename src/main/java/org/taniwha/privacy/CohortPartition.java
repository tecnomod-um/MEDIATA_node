package org.taniwha.privacy;

import org.taniwha.util.AggregateDisclosure;
import java.util.*;
import java.util.function.IntUnaryOperator;

/** Node-local membership equivalence classes; storage is linear in the dataset size. */
public final class CohortPartition {
    private int[] labels;
    public CohortPartition(int[] labels) { this.labels = labels.clone(); }
    public boolean allow(Collection<BitSet> cohorts) {
        int[] next = labels;
        for (BitSet cohort : cohorts) {
            if (cohort == null || cohort.length() > next.length) return false;
            next = refine(next, row -> cohort.get(row) ? 1 : 0);
            if (next == null) return false;
        }
        labels = next;
        return true;
    }

    /** Check all cells and their complement atomically, in one pass over the dataset. */
    boolean allowDisjoint(Collection<BitSet> cells) {
        int[] groups = new int[labels.length];
        int group = 0;
        for (BitSet cell : cells) {
            if (cell.isEmpty() || cell.length() > groups.length) return false;
            group++;
            for (int row = cell.nextSetBit(0); row >= 0; row = cell.nextSetBit(row + 1)) {
                if (groups[row] != 0) return false;
                groups[row] = group;
            }
        }
        int[] refined = refine(labels, row -> groups[row]);
        if (refined == null) return false;
        labels = refined;
        return true;
    }

    private static int[] refine(int[] previous, IntUnaryOperator group) {
        Map<Long, Integer> ids = new HashMap<>();
        int[] refined = new int[previous.length];
        int[] counts = new int[previous.length];
        for (int row = 0; row < previous.length; row++) {
            long key = ((long) previous[row] << 32) | group.applyAsInt(row);
            int id = ids.computeIfAbsent(key, ignored -> ids.size());
            refined[row] = id;
            counts[id]++;
        }
        for (int id = 0; id < ids.size(); id++) {
            if (counts[id] < AggregateDisclosure.MIN_COUNT) return null;
        }
        return refined;
    }
    public int[] labels() { return labels.clone(); }
    public List<BitSet> cells() {
        Map<Integer, BitSet> cells = new HashMap<>();
        for (int row = 0; row < labels.length; row++) cells.computeIfAbsent(labels[row], ignored -> new BitSet()).set(row);
        return new ArrayList<>(cells.values());
    }
}
