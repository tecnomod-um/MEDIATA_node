package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Setter
@Getter
public class FileFilters {
    private String fileName;
    private String nodeId;
    private Map<String, Object> filters;

}
