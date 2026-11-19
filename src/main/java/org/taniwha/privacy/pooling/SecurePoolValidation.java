package org.taniwha.privacy.pooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.GeneralSecurityException;
import java.util.*;

final class SecurePoolValidation {
    private SecurePoolValidation() {}

    static void manifest(SecurePoolManifest manifest,String nodeId,String ownKey) {
        UUID.fromString(manifest.sessionId());
        if(Math.abs(System.currentTimeMillis()-manifest.createdAt())>300_000
                || manifest.participants()==null || manifest.participants().size()<2 || manifest.participants().size()>16
                || manifest.features()==null || manifest.features().isEmpty() || manifest.features().size()>256
                || manifest.sources()==null || manifest.sources().isEmpty() || manifest.sources().size()>64)
            throw new IllegalArgumentException("Invalid secure pooling manifest");
        Set<String> ids=new HashSet<>(),keys=new HashSet<>(),sources=new HashSet<>(),features=new HashSet<>();
        Set<SecurePoolMember> usedColumns=new HashSet<>();
        for(var participant:manifest.participants()) {
            if(participant.nodeId()==null || !participant.nodeId().matches("[A-Za-z0-9._-]{1,80}")
                    || !ids.add(participant.nodeId()) || participant.publicKey()==null || participant.publicKey().length()>4096
                    || !keys.add(participant.publicKey())) throw new IllegalArgumentException("Distinct node identities are required");
        }
        if(manifest.participants().stream().noneMatch(p->p.nodeId().equals(nodeId)&&p.publicKey().equals(ownKey)))
            throw new IllegalArgumentException("Registered node identity differs");
        for(var source:manifest.sources()) {
            if(!ids.contains(source.nodeId()) || !PrivatePoolingPolicy.validName(source.fileName())
                    || !sources.add(key(source.nodeId(),source.fileName()))) throw new IllegalArgumentException("Invalid source");
        }
        for(var feature:manifest.features()) {
            if(!PrivatePoolingPolicy.validName(feature.id()) || !features.add(feature.id())
                    || !Set.of("continuous","categorical","date").contains(feature.type())
                    || feature.members()==null || feature.members().size()<2 || feature.members().size()>64
                    || feature.members().stream().map(SecurePoolMember::nodeId).distinct().count()<2)
                throw new IllegalArgumentException("A pooled feature needs two distinct nodes");
            Set<String> seen=new HashSet<>();
            for(var member:feature.members()) if(!sources.contains(key(member.nodeId(),member.fileName()))
                    || !PrivatePoolingPolicy.validName(member.column()) || !seen.add(key(member.nodeId(),member.fileName())) || !usedColumns.add(member))
                throw new IllegalArgumentException("Invalid feature member");
        }
        if(ids.stream().anyMatch(id->manifest.features().stream().flatMap(f->f.members().stream()).noneMatch(m->m.nodeId().equals(id))))
            throw new IllegalArgumentException("Each participant must contribute");
    }

    static List<SecurePoolOffer> offers(SecurePoolSession session,List<SecurePoolEnvelope> envelopes,ObjectMapper mapper)
            throws java.io.IOException,GeneralSecurityException {
        if(envelopes==null || envelopes.size()!=session.manifest.participants().size()) throw new IllegalArgumentException("All nodes must participate");
        var result=new TreeMap<String,SecurePoolOffer>();
        var exchangeKeys=new HashSet<String>();
        for(var envelope:envelopes) {
            if(envelope==null || envelope.payload()==null || envelope.payload().length()>2_000_000)
                throw new IllegalArgumentException("Invalid signed offer size");
            var offer=mapper.readValue(envelope.payload(),SecurePoolOffer.class);
            var participant=session.manifest.participants().stream().filter(p->p.nodeId().equals(offer.nodeId())).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("Unregistered participant"));
            if(!SecurePoolCrypto.verify(envelope,participant.publicKey()) || !session.manifestHash.equals(offer.manifestHash())
                    || result.put(offer.nodeId(),offer)!=null || !exchangeKeys.add(offer.exchangeKey()))
                throw new IllegalArgumentException("Invalid signed node offer");
            Set<String> expected=new HashSet<>();
            session.manifest.features().stream().filter(f->f.members().stream().anyMatch(m->m.nodeId().equals(offer.nodeId())))
                    .forEach(f->expected.add(f.id()));
            if(offer.features()==null || !offer.features().keySet().equals(expected) || offer.costs()==null
                    || !offer.costs().keySet().equals(session.manifest.sources().stream().filter(s->s.nodeId().equals(offer.nodeId()))
                    .map(SecurePoolSource::fileName).collect(java.util.stream.Collectors.toSet()))
                    || offer.costs().values().stream().anyMatch(v->v==null || !Double.isFinite(v) || v<=0))
                throw new IllegalArgumentException("Incomplete public offer");
            for(var feature:offer.features().values()) {
                if(feature==null) throw new IllegalArgumentException("Missing feature offer");
                if(feature.unavailable()!=null) {
                    if(feature.unavailable().length()>500) throw new IllegalArgumentException("Invalid feature explanation");
                    continue;
                }
                feature.domain().validate();
                if(!Double.isFinite(feature.noiseVariance()) || feature.noiseVariance()<=0) throw new IllegalArgumentException("Invalid noise variance");
                if(feature.noiseComponents()!=null && (feature.noiseComponents().isEmpty() || feature.noiseComponents().size()>64
                        || feature.noiseComponents().stream().anyMatch(v->v==null || !Double.isFinite(v) || v<=0)
                        || Math.abs(feature.noiseComponents().stream().mapToDouble(Double::doubleValue).sum()-feature.noiseVariance())
                        >1e-9*Math.max(1,feature.noiseVariance()))) throw new IllegalArgumentException("Invalid noise components");
            }
            if(offer.nodeId().equals(session.nodeId) && !envelope.equals(session.offer))
                throw new IllegalArgumentException("Own key or source offer was substituted");
        }
        return List.copyOf(result.values());
    }

    static String transcript(List<SecurePoolOffer> offers,ObjectMapper mapper) throws java.io.IOException {
        return SecurePoolCrypto.hash(mapper.writeValueAsString(offers));
    }
    static String key(String node,String file) {return node+"\n"+file;}
}
