package org.taniwha.privacy.pooling;

public record SecurePoolResult(String protocol, String manifestHash, java.util.Map<String,PrivatePoolingHistogram> features, double rowEpsilon, java.util.List<String> participants, java.util.Map<String,String> unavailable) {}
