package org.taniwha.util;

public final class DiscoveryValues {
    private DiscoveryValues() {}

    // A dash is a missing-value placeholder, not a category or a negative number.
    public static boolean isMissing(String value) {
        return value == null || value.isBlank() || value.trim().equalsIgnoreCase("NULL") || value.trim().equals("-");
    }
}
