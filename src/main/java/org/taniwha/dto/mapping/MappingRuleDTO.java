package org.taniwha.dto.mapping;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class MappingRuleDTO {
    private String id;
    private Map<String, Object> logic;
    private RuleResultDTO then;
    private MappingMetadataDTO metadata;
}