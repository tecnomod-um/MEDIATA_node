package org.taniwha.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public class DatasetElementDTO {
    private String column;
    private List<String> values;
    private String dataType;
    private Long integerStep;
    private Boolean missingAllowed;
    private Boolean unlistedCategoriesAllowed;

    public DatasetElementDTO(String column, List<String> values) {
        this.column = column;
        this.values = values;
    }
}
