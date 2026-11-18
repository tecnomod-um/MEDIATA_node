package org.taniwha.privacy.pooling;

import org.springframework.stereotype.Component;
import org.taniwha.service.DatasetElementExtractionService;

import java.io.IOException;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PrivatePoolingMetadata {
    private final DatasetElementExtractionService elements;

    public PrivatePoolingMetadata(DatasetElementExtractionService elements) {
        this.elements = elements;
    }

    PrivatePoolingPolicy policy(Path input, String file, String hash, Map<String, Object> filter) throws IOException {
        if (filter == null || !(filter.get("conditions") instanceof Map<?, ?> conditions) || conditions.size() != 1
                || !(conditions.keySet().iterator().next() instanceof String groupBy)
                || !(conditions.get(groupBy) instanceof Map<?, ?> column)
                || !(column.get("conditions") instanceof List<?> selection) || selection.size() != 1
                || !(selection.get(0) instanceof Map<?, ?> criterion)
                || !(criterion.get("filterType") instanceof String groupType)) return null;
        Map<String, PrivatePoolingDomain> domains = new LinkedHashMap<>();
        for (var element : elements.extractDatasetElements(file, input).getElements()) {
            if (PrivatePoolingPolicy.validName(element.getColumn()))
                PrivatePoolingDomainFactory.fromElement(element).ifPresent(domain -> domains.put(element.getColumn(), domain));
        }
        PrivatePoolingDomain grouping = domains.remove(groupBy);
        if (grouping == null || domains.isEmpty()) return null;
        if (!grouping.type().equals(groupType)) return null;
        if (domains.size() > 256) throw new PrivatePoolingUnavailableException("unsupported-feature-count",
                "Protected summaries support at most 256 published features per dataset.");
        List<String> groups;
        if (grouping.categories() != null) {
            groups = grouping.categories();
        } else if (grouping.values() != null && grouping.values().size() <= 64) {
            groups = grouping.values().stream().map(value -> grouping.type().equals("date")
                    ? LocalDate.ofEpochDay(value.longValue()).toString()
                    : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()).toList();
        } else {
            throw new PrivatePoolingUnavailableException("unsupported-group-domain",
                    "The selected grouping feature needs a published categorical or discrete domain with at most 64 values.");
        }
        var policy = new PrivatePoolingPolicy(file, hash, groupBy, groupType, groups, domains);
        policy.validate();
        return policy;
    }
}
