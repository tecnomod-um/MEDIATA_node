package org.taniwha.dto.mapping;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class MappingCaseDTO {
    private String id;
    private Map<String, Object> when;
    private String outputValue;
    private String outputValueFrom;

    private MappingMetadataDTO metadata;
}