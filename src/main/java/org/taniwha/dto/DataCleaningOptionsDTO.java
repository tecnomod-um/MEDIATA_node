package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class DataCleaningOptionsDTO {
    private boolean removeDuplicates;
    private boolean removeEmptyRows;

    private boolean standardizeDates;
    private String dateOutputFormat;

    private boolean standardizeNumeric;
    private List<String> numericColumns;
    private String numericMode;
}
