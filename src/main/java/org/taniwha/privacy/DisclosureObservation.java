package org.taniwha.privacy;

import java.util.*;
import org.taniwha.util.AlignedNumericData;
import org.taniwha.util.AggregateDisclosure;

// Transient memberships built during parsing; only indices reach the local ledger
public final class DisclosureObservation {
    final DiscoveryDatasetVersion version;
    final BitSet selected = new BitSet();
    int rows;
    private int numericCursor = -1;
    private final AlignedNumericData aligned = new AlignedNumericData();
    private final Map<String, BitSet> numeric = new HashMap<>(), categorical = new HashMap<>();
    private final Map<String, Map<String, BitSet>> exactCells = new HashMap<>();
    private final Set<String> overCellLimit = new HashSet<>();

    public DisclosureObservation(DiscoveryDatasetVersion version) { this.version = version; }
    public void row(Map<String, String> values, boolean included) {
        int index = rows++;
        if (!included) return;
        selected.set(index);
        values.forEach((name, value) -> {
            if (value == null || value.trim().isEmpty() || value.trim().equalsIgnoreCase("NULL")) return;
            categorical.computeIfAbsent(name, ignored -> new BitSet()).set(index);
        });
    }
    public void numeric(Map<String, Double> values) {
        numericCursor = selected.nextSetBit(numericCursor + 1);
        if (numericCursor < 0) throw new IllegalStateException("Invalid observation sequence");
        aligned.addRecord(values);
        values.forEach((name, value) -> {
            if (value == null || !Double.isFinite(value)) return;
            numeric.computeIfAbsent(name, ignored -> new BitSet()).set(numericCursor);
        });
    }
    public Map<String, List<Double>> numericColumns(Set<String> columns) { return aligned.releasedColumns(columns); }

    public void exactCell(String name, String value) {
        if (overCellLimit.contains(name)) return;
        Map<String, BitSet> cells = exactCells.computeIfAbsent(name, ignored -> new HashMap<>());
        cells.computeIfAbsent(value, ignored -> new BitSet()).set(rows - 1);
        if (cells.size() > AggregateDisclosure.MAX_CELLS) {
            exactCells.remove(name);
            overCellLimit.add(name);
        }
    }

    boolean releaseExactCells(String name, Map<String, ? extends Number> counts, CohortPartition partition) {
        Map<String, BitSet> cells = exactCells.get(name);
        if (cells == null || !cells.keySet().equals(counts.keySet())) return false;
        for (var entry : counts.entrySet()) {
            long count = entry.getValue().longValue();
            if (count < AggregateDisclosure.MIN_COUNT || cells.get(entry.getKey()).cardinality() != count) return false;
        }
        return partition.allowDisjoint(cells.values());
    }
    BitSet members(String name, boolean isNumeric) { return copy((isNumeric ? numeric : categorical).get(name)); }
    static BitSet intersect(BitSet x, BitSet y) { if (x == null || y == null) return null; x.and(y); return x; }
    private BitSet copy(BitSet value) { return value == null ? null : (BitSet) value.clone(); }
}
