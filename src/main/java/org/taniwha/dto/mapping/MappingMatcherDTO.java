package org.taniwha.dto.mapping;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MappingMatcherDTO {
    private String sourceId;
    private String column;
    private String matchType;
    private String value;
    private String valueType;
    private Object minValue;
    private Object maxValue;
}