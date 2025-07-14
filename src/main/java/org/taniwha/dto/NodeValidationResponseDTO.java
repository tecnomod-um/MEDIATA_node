package org.taniwha.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@NoArgsConstructor
@Setter
public class NodeValidationResponseDTO {
    private String jwtNodeToken;

    public NodeValidationResponseDTO(String jwtNodeToken) {
        this.jwtNodeToken = jwtNodeToken;
    }
}
