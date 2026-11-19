package org.taniwha.privacy.pooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.taniwha.util.KeyPairUtil;
import java.io.IOException;
import java.security.*;
import java.time.Instant;
import java.util.*;

/**
 * Node-local orchestration; private inputs and ephemeral keys stay in this JVM.
 * Relay confidentiality assumes a trusted identity registry and non-colluding
 * contributing nodes. Dropouts abort; this is not a Byzantine-resilient protocol.
 * Cached protected inputs also limit disclosure from the aggregate itself.
 */
@Service
public class SecurePoolService {
    private static final org.slf4j.Logger logger=org.slf4j.LoggerFactory.getLogger(SecurePoolService.class);
    private final SecurePoolInputs inputs;
    private final SecurePoolRegistry registry;
    private final KeyPairUtil keys;
    private final ObjectMapper mapper;
    private final SecurePoolRebinCache grids;
    private final Map<String,SecurePoolSession> sessions=new HashMap<>();

    public SecurePoolService(SecurePoolInputs inputs,SecurePoolRegistry registry,KeyPairUtil keys,ObjectMapper mapper,SecurePoolRebinCache grids) {
        this.inputs=inputs;this.registry=registry;this.keys=keys;this.mapper=mapper;this.grids=grids;
    }

    public synchronized SecurePoolEnvelope prepare(SecurePoolPrepareRequest request) throws IOException,GeneralSecurityException {
        if(request==null || request.manifest()==null || request.manifest().length()>500_000) throw new IllegalArgumentException("Invalid manifest");
        var manifest=mapper.readValue(request.manifest(),SecurePoolManifest.class);
        SecurePoolValidation.manifest(manifest,request.nodeId(),keys.getPublicKeyBase64());
        String hash=SecurePoolCrypto.hash(request.manifest());
        sessions.values().removeIf(s->s.expires.isBefore(Instant.now()));
        var existing=sessions.get(manifest.sessionId());
        if(existing!=null) {
            if(!existing.manifestHash.equals(hash) || !existing.nodeId.equals(request.nodeId())) throw new IllegalArgumentException("Session identifier reused with a different manifest");
            return existing.offer;
        }
        if(sessions.size()>=32) throw new IllegalStateException("Secure pooling is busy; retry after a session completes");
        registry.verify(request.nodeId(),manifest.participants());
        var data=SecurePoolNodeFeatures.read(manifest,request.nodeId(),inputs,grids);
        var exchange=SecurePoolCrypto.exchangeKey();
        var publicFeatures=new LinkedHashMap<String,SecurePoolFeatureOffer>();
        data.features().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->{
            var h=entry.getValue();publicFeatures.put(entry.getKey(),new SecurePoolFeatureOffer(h.domain(),h.noiseVariance(),h.noiseComponents()));
        });
        data.unavailable().forEach((id,message)->publicFeatures.put(id,new SecurePoolFeatureOffer(null,0,null,message)));
        var offer=new SecurePoolOffer(hash,request.nodeId(),SecurePoolCrypto.publicKey(exchange.getPublic()),publicFeatures,new TreeMap<>(data.costs()));
        var envelope=SecurePoolCrypto.sign(mapper.writeValueAsString(offer),keys.getPrivateKey());
        sessions.put(manifest.sessionId(),new SecurePoolSession(manifest,hash,request.nodeId(),exchange,data.features(),envelope));
        logger.info("Secure pooling prepared: session={} manifest={} participants={}",manifest.sessionId(),hash,manifest.participants().size());
        return envelope;
    }

    public SecurePoolEnvelope mask(SecurePoolRoundRequest request) throws IOException,GeneralSecurityException {
        var session=session(request.sessionId());
        synchronized(session) {
            return SecurePoolRounds.mask(session,SecurePoolValidation.offers(session,request.offers(),mapper),mapper,keys.getPrivateKey(),grids);
        }
    }

    public SecurePoolResult finish(SecurePoolFinishRequest request) throws IOException,GeneralSecurityException {
        var session=session(request.sessionId());
        synchronized(session) {
            var result=SecurePoolRounds.finish(session,SecurePoolValidation.offers(session,request.offers(),mapper),request.contributions(),mapper);
            logger.info("Secure pooling finalized: session={} manifest={} participants={}",request.sessionId(),session.manifestHash,result.participants().size());
            return result;
        }
    }

    public synchronized void discard(String sessionId) {sessions.remove(sessionId);}

    private synchronized SecurePoolSession session(String id) {
        var session=sessions.get(id);
        if(session==null || session.expires.isBefore(Instant.now())) throw new IllegalArgumentException("Secure pooling session expired; restart the complete round");
        return session;
    }
}
