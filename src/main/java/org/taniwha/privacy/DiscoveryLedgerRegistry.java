package org.taniwha.privacy;

import java.util.Map;
import java.util.Set;

record DiscoveryLedgerRegistry(Map<String, String> paths, Set<String> approved, Set<String> initialised) {}
