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
    private final int maxPoints;

    @org.springframework.beans.factory.annotation.Autowired
    public PrivatePoolingMetadata(DatasetElementExtractionService elements) {
        this(elements,128);
    }

    PrivatePoolingMetadata(DatasetElementExtractionService elements,int maxPoints) {
        this.elements = elements;
        this.maxPoints=maxPoints;
    }

    PrivatePoolingPolicy policy(Path input, String file, String hash, Map<String, Object> filter) throws IOException {
        if (filter == null || !(filter.get("conditions") instanceof Map<?, ?> conditions) || conditions.size() != 1
                || !(conditions.keySet().iterator().next() instanceof String groupBy)
                || !(conditions.get(groupBy) instanceof Map<?, ?> column)
                || !(column.get("conditions") instanceof List<?> selection) || selection.size() != 1
                || !(selection.get(0) instanceof Map<?, ?> criterion)
                || !(criterion.get("filterType") instanceof String groupType)) return null;
        var published = elements.extractDatasetElements(file, input).getElements();
        Map<String, PrivatePoolingDomain> domains = domains(published);
        PrivatePoolingDomain grouping = published.stream().filter(element->groupBy.equals(element.getColumn()))
                .findFirst().flatMap(PrivatePoolingDomainFactory::fromElement).orElse(null);
        domains.remove(groupBy);
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

    Map<String, PrivatePoolingDomain> domains(Path input, String file) throws IOException {
        return domains(elements.extractDatasetElements(file,input).getElements());
    }

    private Map<String,PrivatePoolingDomain> domains(List<org.taniwha.dto.DatasetElementDTO> published) {
        Map<String, PrivatePoolingDomain> domains = new LinkedHashMap<>();
        for (var element : published) {
            if (PrivatePoolingPolicy.validName(element.getColumn()))
                PrivatePoolingDomainFactory.fromElement(element,maxPoints).ifPresent(domain -> domains.put(element.getColumn(), domain));
        }
        return domains;
    }
}
