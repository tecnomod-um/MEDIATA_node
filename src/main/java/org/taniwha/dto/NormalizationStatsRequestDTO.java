package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class NormalizationStatsRequestDTO {
    private List<String> columns;
    private DataCleaningOptionsDTO cleaningOptions;
}
