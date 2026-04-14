package org.taniwha.dto.mapping;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DatasetBindingDTO {
    private String sourceId;
    private String nodeId;
    private String elementFileName;
    private List<String> datasets;
}