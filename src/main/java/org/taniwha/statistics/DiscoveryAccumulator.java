package org.taniwha.statistics;

import org.apache.commons.math3.util.Pair;
import java.util.*;

/** Buffers owned by a single file-processing thread, never shared between requests. */
public final class DiscoveryAccumulator {
    public final Map<String, List<Double>> continuous = new HashMap<>();
    public final Map<String, Map<String, Integer>> categorical = new HashMap<>();
    public final Map<String, List<String>> dates = new HashMap<>();
    public final Map<String, Long> missing = new HashMap<>();
    public final List<OmittedFeatureStatistics> omitted = new ArrayList<>();
    public final Map<Pair<String, String>, Map<Pair<String, String>, Integer>> combinations = new HashMap<>();
    public long rows;
}
