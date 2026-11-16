package org.taniwha.privacy.pooling;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

record PrivatePoolingPolicy(String fileName, String sha256, String groupBy, String groupType, List<String> groups,
                            Map<String, PrivatePoolingDomain> features) {
    void validate() {
        if (!validName(fileName) || !fileName.matches("[^/\\\\]+[.](?i:csv|xlsx)")
                || sha256 == null || !sha256.matches("[0-9a-f]{64}") || !validName(groupBy)
                || (!"categorical".equals(groupType) && !"continuous".equals(groupType))
                || groups == null || groups.isEmpty() || groups.size() > 64
                || !groups.stream().allMatch(PrivatePoolingPolicy::validName)
                || new HashSet<>(groups).size() != groups.size()
                || features == null || features.isEmpty() || features.size() > 16
                || features.containsKey(groupBy)) {
            throw new IllegalArgumentException("Invalid private pooling policy");
        }
        features.forEach((name, domain) -> {
            if (!validName(name) || domain == null) throw new IllegalArgumentException("Invalid private pooling feature");
            domain.validate();
        });
    }

    Map<String, Object> filter(String group) {
        return Map.of("operator", "AND", "conditions", Map.of(groupBy, Map.of("conditions",
                List.of(Map.of("type", "equal", "filterType", groupType, "value", group)), "operators", List.of())));
    }

    String groupFor(Map<String, Object> filter, BiPredicate<Map<String, String>, Map<String, Object>> matches) {
        if (filter == null || !(filter.get("conditions") instanceof Map<?, ?> conditions) || conditions.size() != 1
                || !(conditions.get(groupBy) instanceof Map<?, ?> column)
                || !(column.get("conditions") instanceof List<?> selection) || selection.size() != 1
                || !(selection.get(0) instanceof Map<?, ?> criterion)
                || !groupType.equals(criterion.get("filterType")) || !"equal".equals(criterion.get("type"))
                || !(criterion.get("value") instanceof String || criterion.get("value") instanceof Number)) return null;
        String group = criterion.get("value").toString();
        Map<String, Object> selectionFilter = filter(group);
        return groups.stream().filter(known -> matches.test(Map.of(groupBy, known), selectionFilter)).findFirst().orElse(null);
    }

    static boolean validName(String value) {
        return value != null && !value.isBlank() && value.length() <= 160 && value.equals(value.strip());
    }
}
