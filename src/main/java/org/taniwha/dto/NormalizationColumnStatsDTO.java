package org.taniwha.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class NormalizationColumnStatsDTO {
    private boolean present;
    private Double min;
    private Double max;
    private long validCount;
    private long invalidCount;
}
