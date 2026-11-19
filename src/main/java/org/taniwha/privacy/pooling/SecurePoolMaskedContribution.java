package org.taniwha.privacy.pooling;

public record SecurePoolMaskedContribution(String manifestHash, String transcriptHash, String nodeId, String vector) {}
