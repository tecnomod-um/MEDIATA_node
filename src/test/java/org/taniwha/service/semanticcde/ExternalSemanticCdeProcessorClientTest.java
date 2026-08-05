package org.taniwha.service.semanticcde;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSemanticCdeProcessorClientTest {

    @TempDir
    Path tempDir;

    @Test
    void executeSupportsClasspathProcessorLaunch() throws Exception {
        Path script = tempDir.resolve("fake-java.sh");
        Path marker = tempDir.resolve("args.txt");
        Path envMarker = tempDir.resolve("env.txt");
        Files.writeString(script, """
                #!/usr/bin/env bash
                printf '%%s\\n' "$@" > "%s"
                printf '%%s\\n' "$SEMANTIC_CDE_REPOSITORY_URL" > "%s"
                """.formatted(marker, envMarker));
        script.toFile().setExecutable(true);
        script.toFile().setReadable(true);

        SemanticCdeProcessorProperties properties = new SemanticCdeProcessorProperties(
                script.toString(),
                null,
                marker + ":ignored-classpath",
                "org.semanticor.SemanticorApplication",
                "https://semantics.inf.um.es/mediata/scde",
                10
        );
        ExternalSemanticCdeProcessorClient client = new ExternalSemanticCdeProcessorClient(properties);

        SemanticCdeProcessorResult result = client.execute(new SemanticCdeProcessorCommand(
                "https://registry.example/scde-index/definition.ttl",
                tempDir.resolve("input.csv"),
                List.of("MAP-1", "MAP-2"),
                tempDir.resolve("out")
        ));

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(marker))
                .contains("-cp")
                .contains("ignored-classpath")
                .contains("org.semanticor.SemanticorApplication")
                .contains("https://registry.example/scde-index/definition.ttl")
                .contains("MAP-1,MAP-2");
        assertThat(Files.readString(envMarker))
                .contains("https://semantics.inf.um.es/mediata/scde");
    }
}
