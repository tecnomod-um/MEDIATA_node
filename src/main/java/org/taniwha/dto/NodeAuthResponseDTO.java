package org.taniwha.dto;

import lombok.Getter;

@Getter
public class NodeAuthResponseDTO {
    private final String message;

    public NodeAuthResponseDTO(String message) {
        this.message = message;
    }
}
