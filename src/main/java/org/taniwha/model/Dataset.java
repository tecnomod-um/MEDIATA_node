package org.taniwha.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Dataset {

    private String title;
    private String description;
    private String identifier;

    // temporal fields
    private String issued;
    private String modified;
    private String accrualPeriodicity;

    // classification fields
    private List<String> keyword;
    private List<String> theme;
    private List<String> language;

    // references to .org or strings
    private String publisher;
    private String contactPoint;

    // coverage
    private String spatial;
    private String temporal;

    // distributions
    private List<Distribution> distribution;
}
