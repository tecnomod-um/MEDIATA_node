package org.taniwha.privacy.pooling;

import java.util.List;
record SecurePoolField(String id,PrivatePoolingDomain domain,List<Double> noiseComponents) {}
