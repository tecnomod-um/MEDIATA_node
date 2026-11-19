package org.taniwha.privacy.pooling;

import java.io.IOException;
import java.util.*;

final class SecurePoolNodeFeatures {
    private SecurePoolNodeFeatures() {}

    static SecurePoolPrivateData read(SecurePoolManifest manifest,String nodeId,SecurePoolInputs inputs,SecurePoolRebinCache grids) throws IOException {
        var sources=new LinkedHashMap<String,PrivatePoolingRelease>();
        var versions=new HashMap<String,String>();
        var costs=new LinkedHashMap<String,Double>();
        for(var source:manifest.sources()) if(source.nodeId().equals(nodeId)) {
            var versioned=inputs.versionedSource(source);var release=versioned.release();
            versions.put(source.fileName(),versioned.contentHash());
            sources.put(source.fileName(),release);costs.put(source.fileName(),release.rowEpsilon());
        }
        var result=new LinkedHashMap<String,PrivatePoolingHistogram>();
        var unavailable=new LinkedHashMap<String,String>();
        for(var feature:manifest.features()) {
            var members=feature.members().stream().filter(m->m.nodeId().equals(nodeId))
                    .sorted(Comparator.comparing(SecurePoolMember::fileName).thenComparing(SecurePoolMember::column)).toList();
            if(members.isEmpty()) continue;
            try {
                var variables=new HashSet<List<String>>();
                for(var member:members) if(!variables.add(List.of(versions.get(member.fileName()),member.column())))
                    throw new IllegalArgumentException("The same dataset version and variable were selected more than once in this combination");
                var nativeParts=members.stream().map(m->sources.get(m.fileName()).features().get(m.column())).toList();
                if(nativeParts.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Mapped feature has no published protected domain");
                var parts=nativeParts.stream().map(part->SecurePoolInterpretation.as(part,feature.type())).toList();
                var domain=SecurePoolDomains.common(parts.stream().map(PrivatePoolingHistogram::domain).toList());
                if(!domain.type().equals(feature.type())) throw new IllegalArgumentException("Selected type differs from the native domain");
                long[] cells=new long[domain.size()];var components=new ArrayList<Double>();
                for(int index=0;index<parts.size();index++) {
                    var part=parts.get(index);var member=members.get(index);var release=sources.get(member.fileName());
                    long[] aligned=grids.rebin(List.of("native",member,release.group(),release.selectionScope()),part,domain);
                    for(int i=0;i<cells.length;i++) cells[i]=Math.addExact(cells[i],aligned[i]);
                    components.addAll(SecurePoolDomains.offer(part,domain).noiseComponents());
                }
                if(components.size()>64) throw new IllegalArgumentException("Too many source noise components");
                result.put(feature.id(),new PrivatePoolingHistogram(domain,Arrays.stream(cells).boxed().toList(),
                        components.stream().mapToDouble(Double::doubleValue).sum(),List.copyOf(components)));
            } catch(IllegalArgumentException invalid) {
                unavailable.put(feature.id(),invalid.getMessage());
            }
        }
        return new SecurePoolPrivateData(Map.copyOf(result),Map.copyOf(costs),Map.copyOf(unavailable));
    }
}
