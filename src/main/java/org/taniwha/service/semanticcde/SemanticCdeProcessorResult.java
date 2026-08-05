package org.taniwha.service.semanticcde;

public record SemanticCdeProcessorResult(
        int exitCode,
        String log
) {
    public boolean success() {
        return exitCode == 0;
    }
}
