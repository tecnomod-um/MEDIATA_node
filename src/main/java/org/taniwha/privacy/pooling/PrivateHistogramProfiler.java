package org.taniwha.privacy.pooling;

import org.taniwha.service.DataProcessingService;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

/** Streams immutable node-local rows into a fixed protected release. */
final class PrivateHistogramProfiler {
    private static final int VERSION = 4;
    private final DataProcessingService rows;
    private final SecureRandom random;

    PrivateHistogramProfiler(DataProcessingService rows, SecureRandom random) {
        this.rows=rows;
        this.random=random;
    }

    PrivatePoolingSnapshot calculate(Path path, PrivatePoolingPolicy policy, double available, double spent, double configuredAllowance) throws IOException {
        var plan = PrivatePoolingNoisePlan.forFeatures(policy.features(), random, available);
        var firstHeaders = new AtomicReference<List<String>>();
        Map<String, Map<String, long[]>> histograms = new LinkedHashMap<>();
        long[] coverage = {0,0};
        var groupKeys = new LinkedHashMap<String, String>();
        for (String group : policy.groups()) {
            groupKeys.put(policy.groupKey(group), group);
            var counts = new LinkedHashMap<String, long[]>();
            policy.features().forEach((name, domain) -> counts.put(name, new long[domain.size()]));
            histograms.put(group, counts);
        }
        rows.streamRows(path, headers -> {
            if (firstHeaders.get() != null) {
                if (!firstHeaders.get().equals(headers)) {
                    throw new IllegalArgumentException("Protected summaries require matching worksheet headers");
                }
                return;
            }
            firstHeaders.set(List.copyOf(headers));
            if (!headers.contains(policy.groupBy()) || !headers.containsAll(policy.features().keySet()))
                throw new IllegalArgumentException("Published feature metadata does not match dataset columns");
        }, row -> {
            coverage[0]++;
            var counts = histograms.get(groupKeys.get(policy.groupKey(row.get(policy.groupBy()))));
            if (counts != null) {
                coverage[1]++;
                policy.features().forEach((name, domain) -> {
                int cell = domain.indexOf(row.get(name));
                if (domain.structuralZero(cell)) throw new IllegalArgumentException("Row violates published feature metadata");
                counts.get(name)[cell]++;
                });
            }
        });
        var result = new LinkedHashMap<String, PrivatePoolingRelease>();
        histograms.forEach((group, counts) -> {
            var features = new LinkedHashMap<String, PrivatePoolingHistogram>();
            counts.forEach((name, cells) -> {
                var noise = plan.samplers().get(name);
                features.put(name, new PrivatePoolingHistogram(policy.features().get(name),
                        IntStream.range(0, cells.length).mapToObj(cell -> policy.features().get(name).structuralZero(cell)
                                ? 0L : noise.release(cells[cell])).toList(), noise.variance()));
            });
            result.put(group, new PrivatePoolingRelease(VERSION, group, spent + plan.rowEpsilon(), null, features));
        });
        return new PrivatePoolingSnapshot(policy, result, 2, configuredAllowance, coverage[0]>0 && coverage[0]==coverage[1]);
    }

    boolean coversDataset(Path path, PrivatePoolingPolicy policy) throws IOException {
        var groups = new HashSet<String>();
        policy.groups().forEach(group->groups.add(policy.groupKey(group)));
        long[] coverage = {0,0};
        rows.streamRows(path,row->{
            coverage[0]++;
            if (groups.contains(policy.groupKey(row.get(policy.groupBy())))) coverage[1]++;
        });
        return coverage[0]>0 && coverage[0]==coverage[1];
    }

}
