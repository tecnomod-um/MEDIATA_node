package org.taniwha.dto.mapping;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class OneHotOutputDTO {
    private String targetField;
    private Map<String, Object> logic;
    private String trueValue;
    private String falseValue;
    private MappingMetadataDTO metadata;
}