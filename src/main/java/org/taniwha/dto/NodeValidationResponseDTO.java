package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NodeValidationResponseDTO {
    private String jwtNodeToken;

    public NodeValidationResponseDTO(String jwtNodeToken) {
        this.jwtNodeToken = jwtNodeToken;
    }
}
