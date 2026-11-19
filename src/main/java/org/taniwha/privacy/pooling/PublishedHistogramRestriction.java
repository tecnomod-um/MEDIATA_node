package org.taniwha.privacy.pooling;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reuses saved noise while applying the node's current, unfiltered public schema. */
final class PublishedHistogramRestriction {
    private PublishedHistogramRestriction() {}

    static PrivatePoolingSnapshot apply(PrivatePoolingSnapshot snapshot, Map<String, PrivatePoolingDomain> published) {
        var releases = new LinkedHashMap<String, PrivatePoolingRelease>();
        snapshot.releases().forEach((group, release) -> {
            var histograms = new LinkedHashMap<String, PrivatePoolingHistogram>();
            release.features().forEach((name, histogram) -> histograms.put(name, restrict(histogram, published.get(name))));
            releases.put(group, new PrivatePoolingRelease(release.version(), release.group(), release.rowEpsilon(),
                    release.noiseVariance(), histograms, release.selectionScope(), release.cohortCount()));
        });
        var old = snapshot.policy();
        var domains = new LinkedHashMap<String, PrivatePoolingDomain>();
        releases.values().iterator().next().features().forEach((name, histogram) -> domains.put(name, histogram.domain()));
        var policy = new PrivatePoolingPolicy(old.fileName(), old.sha256(), old.groupBy(), old.groupType(), old.groups(), domains);
        policy.validate();
        return new PrivatePoolingSnapshot(policy, releases, 1, snapshot.allocatedAllowance(), snapshot.coversDataset());
    }

    static PrivatePoolingHistogram restrict(PrivatePoolingHistogram saved, PrivatePoolingDomain published) {
        var old = saved.domain();
        if (published == null || !old.type().equals(published.type())) return saved;
        List<Long> counts = new ArrayList<>();
        PrivatePoolingDomain domain;
        if (old.values() != null && published.values() != null && old.values().containsAll(published.values())) {
            published.values().forEach(value -> counts.add(saved.counts().get(old.values().indexOf(value))));
            counts.add(saved.counts().get(old.size() - 1));
            domain = new PrivatePoolingDomain(published.boundaries(), null, published.values(), old.type(),
                    published.missingAllowed(), old.unlistedCategoriesAllowed());
        } else if (old.categories() != null && !published.unlistedCategoriesAllowed()
                && old.categories().containsAll(published.categories())) {
            published.categories().forEach(value -> counts.add(saved.counts().get(old.categories().indexOf(value))));
            counts.add(saved.counts().get(old.size() - 2));
            counts.add(saved.counts().get(old.size() - 1));
            domain = new PrivatePoolingDomain(null, published.categories(), null, old.type(), published.missingAllowed(), false);
        } else {
            counts.addAll(saved.counts());
            domain = new PrivatePoolingDomain(old.boundaries(), old.categories(), old.values(), old.type(),
                    published.missingAllowed(), old.unlistedCategoriesAllowed());
        }
        for (int cell = 0; cell < counts.size(); cell++) if (domain.structuralZero(cell)) counts.set(cell, 0L);
        domain.validate();
        return new PrivatePoolingHistogram(domain, List.copyOf(counts), saved.noiseVariance());
    }
}
