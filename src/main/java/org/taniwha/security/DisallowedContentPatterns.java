package org.taniwha.security;

import lombok.Getter;

@Getter
public enum DisallowedContentPatterns {
    SCRIPT("<script>"), IFRAME("<iframe>"), JAVASCRIPT("javascript:"), VBSCRIPT("vbscript:");

    private final String pattern;

    DisallowedContentPatterns(String pattern) {
        this.pattern = pattern;
    }

    public static boolean containsDisallowed(String content) {
        for (DisallowedContentPatterns pattern : values())
            if (content.contains(pattern.getPattern())) return true;
        return false;
    }
}
