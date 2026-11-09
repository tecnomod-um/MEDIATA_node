package org.taniwha.privacy;

import java.util.*;
import org.taniwha.util.AlignedNumericData;

/** Transient memberships built during parsing; only indices reach the local ledger. */
public final class DisclosureObservation {
    final DiscoveryQueryLedger.Version version;
    final BitSet selected = new BitSet();
    int rows;
    private int numericCursor = -1;
    private final AlignedNumericData aligned = new AlignedNumericData();
    private final Map<String, BitSet> numeric = new HashMap<>(), categorical = new HashMap<>();

    public DisclosureObservation(DiscoveryQueryLedger.Version version) { this.version = version; }
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
    BitSet members(String name, boolean isNumeric) { return copy((isNumeric ? numeric : categorical).get(name)); }
    static BitSet intersect(BitSet x, BitSet y) { if (x == null || y == null) return null; x.and(y); return x; }
    private BitSet copy(BitSet value) { return value == null ? null : (BitSet) value.clone(); }
}
