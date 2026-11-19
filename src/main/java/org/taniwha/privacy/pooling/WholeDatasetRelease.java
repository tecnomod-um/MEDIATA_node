package org.taniwha.privacy.pooling;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Post-processes disjoint saved groups; it does not sample or spend again. */
final class WholeDatasetRelease {
    private WholeDatasetRelease() {}

    static PrivatePoolingRelease from(PrivatePoolingSnapshot snapshot) {
        if (!Boolean.TRUE.equals(snapshot.coversDataset())) return null;
        var histograms = new LinkedHashMap<String,PrivatePoolingHistogram>();
        snapshot.policy().features().forEach((name,domain)->{
            long[] counts = new long[domain.size()];
            var components = new ArrayList<Double>();
            for (var release:snapshot.releases().values()) {
                var histogram = release.features().get(name);
                for (int cell=0;cell<counts.length;cell++) counts[cell]+=histogram.counts().get(cell);
                if (histogram.noiseComponents()!=null) components.addAll(histogram.noiseComponents());
                else components.add(release.version()==1?release.noiseVariance():histogram.noiseVariance());
            }
            var cells = new ArrayList<Long>();
            for (long count:counts) {
                if (Math.abs(count)>1_000_000_000L) throw new PrivatePoolingUnavailableException("whole-release-too-large",
                        "Combined protected histogram exceeds the supported count representation.");
                cells.add(count);
            }
            histograms.put(name,new PrivatePoolingHistogram(domain,List.copyOf(cells),
                    components.stream().mapToDouble(Double::doubleValue).sum(),List.copyOf(components)));
        });
        double epsilon=snapshot.releases().values().iterator().next().rowEpsilon();
        PrivatePoolingCount cohort = null;
        if (snapshot.releases().values().stream().allMatch(release -> release.cohortCount() != null)) {
            long count = 0;
            var components = new ArrayList<Double>();
            for (var release : snapshot.releases().values()) {
                count = Math.addExact(count, release.cohortCount().count());
                components.addAll(release.cohortCount().noiseComponents());
            }
            if (Math.abs(count) > 1_000_000_000L) throw new PrivatePoolingUnavailableException("whole-release-too-large", "Combined protected cohort exceeds the supported count representation.");
            cohort = new PrivatePoolingCount(count, components.stream().mapToDouble(Double::doubleValue).sum(), List.copyOf(components));
        }
        return new PrivatePoolingRelease(cohort == null ? 4 : 5,"all",epsilon,null,histograms,"dataset",cohort);
    }
}
