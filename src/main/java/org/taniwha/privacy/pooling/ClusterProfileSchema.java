package org.taniwha.privacy.pooling;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

final class ClusterProfileSchema {
    private static final int CLUSTER_LIMIT = 64;
    private static final List<String> GROUPS = IntStream.range(0, CLUSTER_LIMIT).mapToObj(Integer::toString).toList();
    private static final PrivatePoolingDomain AGE = numericalRange(120);
    private static final PrivatePoolingDomain BARTHEL = numericalRange(100);
    private static final PrivatePoolingDomain SEX = new PrivatePoolingDomain(null, List.of("Female", "Male"));
    private static final PrivatePoolingDomain STROKE = new PrivatePoolingDomain(null, List.of("Ischemic", "Non-Ischemic"));

    private ClusterProfileSchema() {}

    static PrivatePoolingPolicy fromHeaders(List<String> headers, String file, String digest) {
        Map<String, String> columns = new LinkedHashMap<>();
        headers.forEach(header -> columns.putIfAbsent(normalize(header), header));
        String group = column(columns, "assignedcluster", "clusterid", "clusterassignment", "cluster");
        if (group == null) return null;

        Map<String, PrivatePoolingDomain> features = new LinkedHashMap<>();
        add(features, AGE, column(columns, "age", "patientage", "ageyears"));
        add(features, BARTHEL, column(columns, "bartheladmission", "barthelindextotal", "bartheltotal", "barthelindex", "bitotal", "barthel"));
        add(features, SEX, column(columns, "sex", "biologicalsex"));
        add(features, STROKE, column(columns, "stroketype", "typeofstroke", "strokecategory"));
        return features.isEmpty() ? null : new PrivatePoolingPolicy(file, digest, group, "continuous", GROUPS, features);
    }

    private static void add(Map<String, PrivatePoolingDomain> features, PrivatePoolingDomain domain, String name) {
        if (name != null) features.put(name, domain);
    }

    private static String column(Map<String, String> columns, String... candidates) {
        for (String candidate : candidates) {
            if (columns.containsKey(candidate)) return columns.get(candidate);
        }
        return null;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static PrivatePoolingDomain numericalRange(int max) {
        return new PrivatePoolingDomain(IntStream.rangeClosed(0, max / 5).mapToObj(i -> 5.0 * i).toList(), null);
    }
}
