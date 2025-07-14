package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FileMappingsDTO {
    private String configs;
    private String fileMappings;
    private DataCleaningOptionsDTO cleaningOptions;
}
