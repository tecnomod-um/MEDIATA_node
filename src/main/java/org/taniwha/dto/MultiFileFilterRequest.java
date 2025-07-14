package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class MultiFileFilterRequest {
    private List<FileFilters> multipleFileFilters;
}
