package org.taniwha.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Dataset {

    private String uri;

    // Existing/common DCAT/DCT fields
    private String title;
    private String description;
    private String identifier;

    private String issued;
    private String modified;
    private String accrualPeriodicity;

    private List<Object> keyword;
    private List<Object> theme;
    private List<Object> language;

    private Object publisher;
    private Object contactPoint;

    private Object spatial;
    private Object temporal;

    private List<Distribution> distribution;

    // HealthDCAT / EHDS fields
    private Object accessRights;
    private List<Object> applicableLegislation;
    private List<Object> codeValues;
    private List<Object> codingSystem;
    private List<Object> conformsTo;
    private Object custodian;
    private Boolean hasPersonalData;
    private Object healthDataAccessBody;
    private List<Object> healthCategory;
    private List<Object> healthTheme;
    private Boolean isStructured;
    private Long numberOfRecords;
    private Long numberOfUniqueIndividuals;
    private Object provenance;
    private List<Object> type;
    private List<Variable> variables;
    private Object wasGeneratedBy;

    @JsonIgnore
    private Map<String, Object> additionalProperties = new LinkedHashMap<>();

    @JsonAnyGetter
    public Map<String, Object> any() {
        return additionalProperties;
    }
}