package org.taniwha.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Map;

@Getter
@AllArgsConstructor
public class NormalizationStatsResponseDTO {
    private Map<String, NormalizationColumnStatsDTO> columns;
}
