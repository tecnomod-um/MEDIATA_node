package org.taniwha.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FileInfoDto {
    private String name;
    private long sizeBytes;
    private long createdAtMs;
    private long lastModifiedAtMs;
}
