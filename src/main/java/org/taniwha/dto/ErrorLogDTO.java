package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class ErrorLogDTO {
    private String error;
    private String info;
    private LocalDateTime timestamp;
}
