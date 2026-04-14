package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;
import org.taniwha.dto.mapping.MappingSpecDTO;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class FileMappingsDTO {
    private Map<String, List<String>> fileMappings;
    private MappingSpecDTO mappingSpec;
    private DataCleaningOptionsDTO cleaningOptions;
}