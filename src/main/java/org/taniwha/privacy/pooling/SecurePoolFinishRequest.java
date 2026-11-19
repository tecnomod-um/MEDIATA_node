package org.taniwha.privacy.pooling;

public record SecurePoolFinishRequest(String sessionId, java.util.List<SecurePoolEnvelope> offers, java.util.List<SecurePoolEnvelope> contributions) {}
