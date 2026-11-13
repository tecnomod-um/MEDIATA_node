package org.taniwha.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CleaningValidationResponseDTO {
    private boolean valid;
    private int inputRows;
    private int outputRows;
    private int outputColumns;
}
