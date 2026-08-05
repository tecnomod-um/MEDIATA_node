package org.taniwha.service.semanticcde;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SemanticCdeProcessorProperties {

    private final String javaCommand;
    private final java.nio.file.Path jarPath;
    private final String classpath;
    private final String mainClass;
    private final String semanticCdeRepositoryUrl;
    private final long timeoutSeconds;

    public SemanticCdeProcessorProperties(
            @Value("${semantic.cde.processor.java-command:java}") String javaCommand,
            @Value("${semantic.cde.processor.jar-path:}") String jarPath,
            @Value("${semantic.cde.processor.classpath:}") String classpath,
            @Value("${semantic.cde.processor.main-class:org.semanticor.SemanticorApplication}") String mainClass,
            @Value("${semantic.cde.repository-url:https://semantics.inf.um.es/mediata/scde}") String semanticCdeRepositoryUrl,
            @Value("${semantic.cde.processor.timeout-seconds:300}") long timeoutSeconds
    ) {
        this.javaCommand = javaCommand;
        this.jarPath = jarPath == null || jarPath.isBlank() ? null : java.nio.file.Path.of(jarPath).normalize();
        this.classpath = classpath == null || classpath.isBlank() ? null : classpath;
        this.mainClass = mainClass;
        this.semanticCdeRepositoryUrl = normalizeRepositoryUrl(semanticCdeRepositoryUrl);
        this.timeoutSeconds = timeoutSeconds;
    }

    public String javaCommand() {
        return javaCommand;
    }

    public java.nio.file.Path jarPath() {
        return jarPath;
    }

    public String classpath() {
        return classpath;
    }

    public String mainClass() {
        return mainClass;
    }

    public String semanticCdeRepositoryUrl() {
        return semanticCdeRepositoryUrl;
    }

    public long timeoutSeconds() {
        return timeoutSeconds;
    }

    private static String normalizeRepositoryUrl(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            return null;
        }
        return repositoryUrl.replaceAll("/+$", "");
    }
}
