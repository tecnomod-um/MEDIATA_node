package org.taniwha.privacy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import java.util.Set;

@JsonIgnoreProperties("approved")
record DiscoveryLedgerRegistry(Map<String, String> paths, Set<String> initialised) {}
