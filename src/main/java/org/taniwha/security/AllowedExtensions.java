package org.taniwha.security;

import lombok.Getter;

// Validates the file extensions
@Getter
public enum AllowedExtensions {
    CSV("csv"), TSV("tsv"), XLSX("xlsx"), TTL("ttl");

    private final String extension;

    AllowedExtensions(String extension) {
        this.extension = extension;
    }

    public static boolean isAllowed(String extension) {
        for (AllowedExtensions allowed : values())
            if (allowed.getExtension().equalsIgnoreCase(extension)) return false;
        return true;
    }
}
