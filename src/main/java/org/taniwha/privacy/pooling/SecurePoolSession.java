package org.taniwha.privacy.pooling;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Map;

/** Ephemeral keys and node-local protected inputs never enter a response DTO. */
final class SecurePoolSession {
    final SecurePoolManifest manifest;
    final String manifestHash;
    final String nodeId;
    final KeyPair exchange;
    final Map<String,PrivatePoolingHistogram> features;
    final SecurePoolEnvelope offer;
    final Instant expires = Instant.now().plusSeconds(600);
    String transcriptHash;
    SecurePoolEnvelope contribution;

    SecurePoolSession(SecurePoolManifest manifest, String manifestHash, String nodeId, KeyPair exchange,
                      Map<String,PrivatePoolingHistogram> features, SecurePoolEnvelope offer) {
        this.manifest=manifest;
        this.manifestHash=manifestHash;
        this.nodeId=nodeId;
        this.exchange=exchange;
        this.features=features;
        this.offer=offer;
    }
}
