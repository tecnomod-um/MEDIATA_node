package org.taniwha.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Distribution {

    private String uri;

    private String title;
    private String description;

    private Object format;
    private Object license;
    private Object downloadURL;

    private Object accessURL;
    private Object mediaType;
    private Object byteSize;
    private Object availability;
    private String issued;
    private String modified;
    private Object conformsTo;
    private Object accessService;

    @JsonIgnore
    private Map<String, Object> additionalProperties = new LinkedHashMap<>();

    @JsonAnyGetter
    public Map<String, Object> any() {
        return additionalProperties;
    }
}