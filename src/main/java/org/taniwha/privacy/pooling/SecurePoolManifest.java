package org.taniwha.privacy.pooling;

public record SecurePoolManifest(String sessionId, long createdAt, java.util.List<SecurePoolParticipant> participants, java.util.List<SecurePoolSource> sources, java.util.List<SecurePoolFeature> features) {}
