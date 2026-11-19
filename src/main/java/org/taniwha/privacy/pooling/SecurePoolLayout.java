package org.taniwha.privacy.pooling;

import java.util.*;

final class SecurePoolLayout {
    private SecurePoolLayout() {}

    static List<SecurePoolField> fields(SecurePoolManifest manifest,List<SecurePoolOffer> offers) {
        var result=new ArrayList<SecurePoolField>();
        for(var feature:manifest.features().stream().sorted(Comparator.comparing(SecurePoolFeature::id)).toList()) {
            try {result.add(field(feature,offers));} catch(IllegalArgumentException unsupported) {
                // The same deterministic explanation is returned for this field;
                // other independent combinations remain usable.
            }
        }
        if(length(result)>65_536) throw new IllegalArgumentException("Secure pooling vector exceeds the supported size");
        return List.copyOf(result);
    }
    private static SecurePoolField field(SecurePoolFeature feature,List<SecurePoolOffer> offers) {
        var inputs=offers.stream().map(o->o.features().get(feature.id())).filter(Objects::nonNull).toList();
        if(inputs.stream().anyMatch(o->o.unavailable()!=null)) throw new IllegalArgumentException(inputs.stream().map(SecurePoolFeatureOffer::unavailable).filter(Objects::nonNull).findFirst().orElseThrow());
        if(inputs.stream().anyMatch(o->!o.domain().type().equals(feature.type()))) throw new IllegalArgumentException("Mapped type differs from native metadata");
        var domain=SecurePoolDomains.common(inputs.stream().map(SecurePoolFeatureOffer::domain).toList());
        var components=new ArrayList<Double>();
        for(var input:inputs) {
            var shape=new PrivatePoolingHistogram(input.domain(),Collections.nCopies(input.domain().size(),0L),input.noiseVariance(),input.noiseComponents());
            components.addAll(SecurePoolDomains.offer(shape,domain).noiseComponents());
        }
        if(components.size()>64) throw new IllegalArgumentException("Too many noise components for the common grid");
        return new SecurePoolField(feature.id(),domain,List.copyOf(components));
    }
    static int length(List<SecurePoolField> fields) {return fields.stream().mapToInt(f->f.domain().size()).sum();}

    static Map<String,String> unavailable(SecurePoolManifest manifest,List<SecurePoolOffer> offers) {
        var result=new LinkedHashMap<String,String>();
        for(var feature:manifest.features()) {
            try {field(feature,offers);} catch(IllegalArgumentException unsupported) {result.put(feature.id(),unsupported.getMessage());}
        }
        return result;
    }
}
