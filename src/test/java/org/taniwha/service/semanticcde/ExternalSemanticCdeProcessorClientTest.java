package org.taniwha.service.semanticcde;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSemanticCdeProcessorClientTest {

    @TempDir
    Path tempDir;

    @Test
    void executeSupportsClasspathProcessorLaunch() throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Path classes = Path.of(FakeProcessor.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        SemanticCdeProcessorProperties properties = new SemanticCdeProcessorProperties(
                java.toString(),
                null,
                classes + File.pathSeparator + "ignored-classpath",
                FakeProcessor.class.getName(),
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
        assertThat(result.log())
                .contains("ignored-classpath")
                .contains(FakeProcessor.class.getName())
                .contains("https://registry.example/scde-index/definition.ttl")
                .contains("MAP-1,MAP-2");
        assertThat(result.log())
                .contains("https://semantics.inf.um.es/mediata/scde");
    }

    /** Real Java subprocess fixture, so the launch contract is exercised on every OS. */
    public static class FakeProcessor {
        public static void main(String[] args) {
            System.out.println(FakeProcessor.class.getName());
            System.out.println(System.getProperty("java.class.path"));
            System.out.println(String.join("\n", args));
            System.out.println(System.getenv("SEMANTIC_CDE_REPOSITORY_URL"));
        }
    }
}
