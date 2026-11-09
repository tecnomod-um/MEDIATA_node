package org.taniwha.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Keeps numeric observations aligned by record for the existing pairwise calculators. */
public final class AlignedNumericData {
    private final Map<String, List<Double>> columns = new HashMap<>();
    private int records;

    public void addRecord(Map<String, Double> values) {
        for (String name : values.keySet()) {
            columns.computeIfAbsent(name, ignored -> new ArrayList<>(Collections.nCopies(records, null)));
        }
        columns.forEach((name, column) -> column.add(values.get(name)));
        records++;
    }

    /** Only features released by the normal suppression/type checks may contribute. */
    public Map<String, List<Double>> releasedColumns(Set<String> releasedFeatures) {
        return columns.entrySet().stream().filter(entry -> releasedFeatures.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
