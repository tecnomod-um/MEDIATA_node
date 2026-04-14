package org.taniwha.dto.mapping;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class MappingDefinitionDTO {
    private String id;
    private String targetField;
    private String groupName;
    private String mappingType;
    private List<MappingInputDTO> inputs;
    private List<MappingRuleDTO> rules;
    private List<OneHotOutputDTO> outputs;
    private MappingMetadataDTO metadata;
    private String sourceConfigFile;
    private boolean removeSourceColumns;
}