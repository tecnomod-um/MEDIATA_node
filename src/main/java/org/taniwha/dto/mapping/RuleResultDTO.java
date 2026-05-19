package org.taniwha.dto.mapping;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RuleResultDTO {
    private String kind;
    private String value;
    private String sourceId;
    private String column;
}