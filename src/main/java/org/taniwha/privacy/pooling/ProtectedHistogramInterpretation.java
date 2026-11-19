package org.taniwha.privacy.pooling;

import java.math.BigDecimal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.IntStream;
import org.taniwha.util.NumberUtil;

/** Changes the interpretation of a cached release without reading rows or drawing new noise. */
final class ProtectedHistogramInterpretation {
    private ProtectedHistogramInterpretation() {}

    static PrivatePoolingRelease release(PrivatePoolingRelease release, Map<String,String> types) {
        if (types == null || types.isEmpty()) return release;
        var features = new LinkedHashMap<>(release.features());
        types.forEach((name,type) -> {
            var input = features.get(name);
            if (input == null) return;
            try { features.put(name, as(input,type)); }
            catch (IllegalArgumentException unsupported) {
                // Retain the native domain so an unsupported interpretation
                // cannot be mistaken for converted data or hide other fields.
            }
        });
        return new PrivatePoolingRelease(release.version(),release.group(),release.rowEpsilon(),release.noiseVariance(),
                Map.copyOf(features),release.selectionScope(),release.cohortCount());
    }

    static PrivatePoolingHistogram as(PrivatePoolingHistogram input, String type) {
        if (type == null || !List.of("continuous","categorical","date").contains(type))
            throw new IllegalArgumentException("Invalid feature interpretation");
        var domain = input.domain();
        if (domain.type().equals(type)) return input;
        PrivatePoolingDomain interpreted;
        var counts = new ArrayList<Long>();
        if (type.equals("categorical") && domain.type().equals("continuous") && domain.values() != null) {
            var labels = domain.values().stream().map(value -> BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()).toList();
            interpreted = new PrivatePoolingDomain(null, labels, null, type, domain.missingAllowed(), false);
            counts.addAll(input.counts().subList(0, domain.values().size()));
            counts.add(0L); // No unlisted score exists in the published point domain.
            counts.add(input.counts().get(input.counts().size() - 1));
        } else if (type.equals("continuous") && domain.type().equals("categorical") && !domain.unlistedCategoriesAllowed()) {
            var values = numericLabels(domain.categories());
            if (values.stream().distinct().count() != values.size())
                throw new IllegalArgumentException("Category codes do not identify distinct numerical scores");
            var order = IntStream.range(0, values.size()).boxed().sorted(Comparator.comparing(values::get)).toList();
            var points = order.stream().map(values::get).toList();
            var edges = new ArrayList<Double>();
            edges.add(Math.max(-1e9, points.get(0) - .5));
            for (int i = 1; i < points.size(); i++) edges.add((points.get(i - 1) + points.get(i)) / 2);
            edges.add(Math.min(1e9, points.get(points.size() - 1) + .5));
            interpreted = new PrivatePoolingDomain(edges, null, points, type, domain.missingAllowed(), true);
            order.forEach(index -> counts.add(input.counts().get(index)));
            counts.add(input.counts().get(input.counts().size() - 1));
        } else {
            throw new IllegalArgumentException("The cached domain cannot preserve this interpretation; binned intervals cannot recover individual categories");
        }
        interpreted.validate();
        return new PrivatePoolingHistogram(interpreted, List.copyOf(counts), input.noiseVariance(), input.noiseComponents());
    }

    private static List<Double> numericLabels(List<String> labels) {
        var values = new ArrayList<Double>();
        for (var label : labels) {
            try {
                double value = NumberUtil.parseDouble(label);
                if (!Double.isFinite(value) || Math.abs(value) > 1e9) throw new IllegalArgumentException("Invalid numerical category");
                values.add(value);
            } catch (ParseException invalid) {
                throw new IllegalArgumentException("Category labels are not numerical scores", invalid);
            }
        }
        return values;
    }
}
