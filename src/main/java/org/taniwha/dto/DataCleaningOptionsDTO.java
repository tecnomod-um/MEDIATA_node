package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class DataCleaningOptionsDTO {
    private boolean removeDuplicates;
    private boolean removeEmptyRows;
    private boolean standardizeDates;
    private String dateOutputFormat;
}
