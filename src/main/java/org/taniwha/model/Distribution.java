package org.taniwha.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Distribution {

    private String title;
    private String description;
    private String format;
    private String license;
    private String downloadURL;
}
