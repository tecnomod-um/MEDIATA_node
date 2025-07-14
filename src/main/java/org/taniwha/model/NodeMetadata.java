package org.taniwha.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodeMetadata {
    @JsonProperty("@context")
    private String context;
    private List<Dataset> dataset;
}
