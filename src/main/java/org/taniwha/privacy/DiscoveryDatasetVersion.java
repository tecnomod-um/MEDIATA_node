package org.taniwha.privacy;

import java.nio.file.Path;

public record DiscoveryDatasetVersion(Path path, String pathKey, String contentKey) {}
