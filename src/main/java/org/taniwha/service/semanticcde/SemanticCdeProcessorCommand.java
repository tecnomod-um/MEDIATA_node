package org.taniwha.service.semanticcde;

import java.util.List;
import java.nio.file.Path;

public record SemanticCdeProcessorCommand(
        String semanticCdeResource,
        Path inputFile,
        List<String> mappingIds,
        Path outputPath
) {
}
