package org.taniwha.service.semanticcde;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ExternalSemanticCdeProcessorClient implements SemanticCdeProcessorClient {

    private final SemanticCdeProcessorProperties properties;

    public ExternalSemanticCdeProcessorClient(SemanticCdeProcessorProperties properties) {
        this.properties = properties;
    }

    @Override
    public SemanticCdeProcessorResult execute(SemanticCdeProcessorCommand command) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        args.add(properties.javaCommand());
        if (properties.classpath() != null) {
            args.add("-cp");
            args.add(properties.classpath());
            args.add(properties.mainClass());
        } else {
            if (properties.jarPath() == null) {
                throw new IllegalStateException("Semantic-CDE processor jar or classpath is not configured.");
            }
            if (!Files.isRegularFile(properties.jarPath())) {
                throw new IllegalStateException("Semantic-CDE processor jar does not exist: " + properties.jarPath());
            }
            args.add("-jar");
            args.add(properties.jarPath().toString());
        }
        args.add(command.semanticCdeResource());
        args.add(command.inputFile().toString());
        args.add(String.join(",", command.mappingIds()));
        args.add(command.outputPath().toString());

        Path logFile = Files.createTempFile("semantic-cde-processor-", ".log");
        ProcessBuilder processBuilder = new ProcessBuilder(args)
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        if (properties.semanticCdeRepositoryUrl() != null) {
            processBuilder.environment().put("SEMANTIC_CDE_REPOSITORY_URL", properties.semanticCdeRepositoryUrl());
        }
        Process process = processBuilder.start();

        try {
            boolean finished = process.waitFor(properties.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Semantic-CDE processor timed out after "
                        + Duration.ofSeconds(properties.timeoutSeconds()));
            }

            String log = Files.readString(logFile, StandardCharsets.UTF_8);
            return new SemanticCdeProcessorResult(process.exitValue(), log);
        } finally {
            Files.deleteIfExists(logFile);
        }
    }
}
