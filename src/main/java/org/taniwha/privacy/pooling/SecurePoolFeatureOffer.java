package org.taniwha.privacy.pooling;

public record SecurePoolFeatureOffer(PrivatePoolingDomain domain, double noiseVariance, java.util.List<Double> noiseComponents, String unavailable) {
    public SecurePoolFeatureOffer(PrivatePoolingDomain domain,double noiseVariance,java.util.List<Double> components) {
        this(domain,noiseVariance,components,null);
    }
}
