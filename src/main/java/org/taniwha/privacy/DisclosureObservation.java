package org.taniwha.privacy;

import java.util.*;
import org.taniwha.util.AggregateDisclosure;
import org.taniwha.util.AlignedNumericData;
import org.taniwha.statistics.ContinuousFeatureStatistics;

/** Transient memberships built during parsing; only indices reach the local ledger. */
public final class DisclosureObservation {
    final DiscoveryQueryLedger.Version version;
    final BitSet selected = new BitSet();
    int rows;
    private int numericCursor = -1;
    private final AlignedNumericData aligned = new AlignedNumericData();
    private final Map<String, BitSet> numeric = new HashMap<>(), categorical = new HashMap<>();
    private final Map<String, Map<Object, BitSet>> numericCells = new HashMap<>(), categoryCells = new HashMap<>();
    private final Set<String> largeNumeric = new HashSet<>(), largeCategory = new HashSet<>();

    public DisclosureObservation(DiscoveryQueryLedger.Version version) { this.version = version; }
    public void row(Map<String, String> values, boolean included) {
        int index = rows++;
        if (!included) return;
        selected.set(index);
        values.forEach((name, value) -> {
            if (value == null || value.trim().isEmpty() || value.trim().equalsIgnoreCase("NULL")) return;
            categorical.computeIfAbsent(name, ignored -> new BitSet()).set(index);
            cell(categoryCells, largeCategory, name, value.trim(), index);
        });
    }
    public void numeric(Map<String, Double> values) {
        numericCursor = selected.nextSetBit(numericCursor + 1);
        if (numericCursor < 0) throw new IllegalStateException("Invalid observation sequence");
        aligned.addRecord(values);
        values.forEach((name, value) -> {
            if (value == null || !Double.isFinite(value)) return;
            numeric.computeIfAbsent(name, ignored -> new BitSet()).set(numericCursor);
            cell(numericCells, largeNumeric, name, value, numericCursor);
        });
    }
    public Map<String, List<Double>> numericColumns(Set<String> columns) { return aligned.releasedColumns(columns); }
    List<BitSet> histogram(ContinuousFeatureStatistics feature) {
        int bins = feature.getHistogram().size();
        if (bins == 0) return List.of();
        if (bins > 1 && (!(feature.getHistogramBinWidth() > 0) || !Double.isFinite(feature.getHistogramBinWidth()))) return null;
        List<BitSet> cohorts = new ArrayList<>(); for (int i = 0; i < bins; i++) cohorts.add(new BitSet());
        List<Double> values = aligned.releasedColumns(Set.of(feature.getFeatureName())).get(feature.getFeatureName());
        if (values == null) return null;
        int rawIndex = selected.nextSetBit(0);
        for (Double value : values) {
            if (value != null && Double.isFinite(value)) {
                int bin = bins == 1 ? 0 : Math.min((int) ((value - feature.getMin()) / feature.getHistogramBinWidth()), bins - 1);
                if (bin < 0) return null;
                cohorts.get(bin).set(rawIndex);
            }
            rawIndex = selected.nextSetBit(rawIndex + 1);
        }
        for (int i = 0; i < bins; i++) if (cohorts.get(i).cardinality() != feature.getHistogram().get(i)) return null;
        return cohorts;
    }
    private void cell(Map<String, Map<Object, BitSet>> groups, Set<String> large, String name, Object value, int index) {
        if (large.contains(name)) return;
        Map<Object, BitSet> values = groups.computeIfAbsent(name, ignored -> new HashMap<>());
        values.computeIfAbsent(value, ignored -> new BitSet()).set(index);
        if (values.size() > AggregateDisclosure.MAX_CELLS) { groups.remove(name); large.add(name); }
    }
    BitSet members(String name, boolean isNumeric) { return copy((isNumeric ? numeric : categorical).get(name)); }
    List<BitSet> impliedMomentCells(String name) {
        return MomentDisclosure.univariateCells(numericCells.get(name));
    }
    List<BitSet> impliedMomentCells(String x, String y) {
        return MomentDisclosure.pairedCells(numericCells.get(x), numericCells.get(y));
    }
    BitSet value(String name, Object value, boolean isNumeric) {
        return copy((isNumeric ? numericCells : categoryCells).getOrDefault(name, Map.of()).get(value));
    }
    static BitSet intersect(BitSet x, BitSet y) { if (x == null || y == null) return null; x.and(y); return x; }
    private BitSet copy(BitSet value) { return value == null ? null : (BitSet) value.clone(); }
}
