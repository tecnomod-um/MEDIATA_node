package org.taniwha.privacy.pooling;

public record SecurePoolOffer(String manifestHash, String nodeId, String exchangeKey, java.util.Map<String,SecurePoolFeatureOffer> features, java.util.Map<String,Double> costs) {}
