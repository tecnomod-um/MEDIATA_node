package org.taniwha.privacy.pooling;
import java.util.Map;
record SecurePoolPrivateData(Map<String,PrivatePoolingHistogram> features,Map<String,Double> costs,Map<String,String> unavailable) {}
