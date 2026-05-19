package org.taniwha.dto.mapping;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class MappingSpecDTO {
    private String specVersion;
    private String ruleLanguage;
    private Map<String, Object> targetSchema;
    private List<MappingSourceDTO> sources;
    private List<DatasetBindingDTO> datasetBindings;
    private List<MappingDefinitionDTO> mappings;
}