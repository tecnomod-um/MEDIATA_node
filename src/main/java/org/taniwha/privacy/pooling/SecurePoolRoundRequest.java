package org.taniwha.privacy.pooling;

public record SecurePoolRoundRequest(String sessionId, java.util.List<SecurePoolEnvelope> offers) {}
