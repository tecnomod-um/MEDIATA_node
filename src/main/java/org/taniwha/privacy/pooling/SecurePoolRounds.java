package org.taniwha.privacy.pooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.*;
import java.util.*;

final class SecurePoolRounds {
    private SecurePoolRounds() {}

    static SecurePoolEnvelope mask(SecurePoolSession session,List<SecurePoolOffer> offers,
                                   ObjectMapper mapper,PrivateKey signingKey,SecurePoolRebinCache grids)
            throws IOException,GeneralSecurityException {
        String transcript=SecurePoolValidation.transcript(offers,mapper);
        if(session.transcriptHash!=null && !session.transcriptHash.equals(transcript))
            throw new IllegalArgumentException("Session roster or schema changed");
        session.transcriptHash=transcript;
        if(session.contribution!=null) return session.contribution;
        var fields=SecurePoolLayout.fields(session.manifest,offers);
        long[] values=new long[SecurePoolLayout.length(fields)];int offset=0;
        for(var field:fields) {
            var input=session.features.get(field.id());
            if(input!=null) {
                var feature=session.manifest.features().stream().filter(f->f.id().equals(field.id())).findFirst().orElseThrow();
                var members=feature.members().stream().filter(m->m.nodeId().equals(session.nodeId))
                        .sorted(Comparator.comparing(SecurePoolMember::fileName).thenComparing(SecurePoolMember::column)).toList();
                // Padding depends on the saved private input and public grid,
                // not incidental JSON ordering or spelling of the filter.
                var columns=members.stream().map(member->List.of(member.fileName(),member.column())).toList();
                var counts=grids.rebin(List.of("aligned",columns),input,field.domain());
                System.arraycopy(counts,0,values,offset,counts.length);
            }
            offset+=field.domain().size();
        }
        for(var peer:offers) if(!peer.nodeId().equals(session.nodeId)) {
            boolean add=session.nodeId.compareTo(peer.nodeId())<0;
            String pair=add?session.nodeId+"\n"+peer.nodeId():peer.nodeId()+"\n"+session.nodeId;
            String context=session.manifestHash+"\n"+transcript+"\n"+pair;
            SecurePoolCrypto.mask(values,session.exchange.getPrivate(),peer.exchangeKey(),context,add);
        }
        var contribution=new SecurePoolMaskedContribution(session.manifestHash,transcript,session.nodeId,SecurePoolCrypto.encode(values));
        session.contribution=SecurePoolCrypto.sign(mapper.writeValueAsString(contribution),signingKey);
        return session.contribution;
    }

    static SecurePoolResult finish(SecurePoolSession session,List<SecurePoolOffer> offers,List<SecurePoolEnvelope> envelopes,
                                   ObjectMapper mapper) throws IOException,GeneralSecurityException {
        String transcript=SecurePoolValidation.transcript(offers,mapper);
        if(session.contribution==null || !transcript.equals(session.transcriptHash))
            throw new IllegalArgumentException("Node has not completed this masked round");
        if(envelopes==null || envelopes.size()!=offers.size()) throw new IllegalArgumentException("A participant dropped out; no partial result can be released");
        var fields=SecurePoolLayout.fields(session.manifest,offers);
        long[] total=new long[SecurePoolLayout.length(fields)];Set<String> seen=new HashSet<>();
        for(var envelope:envelopes) {
            if(envelope==null || envelope.payload()==null || envelope.payload().length()>2_000_000)
                throw new IllegalArgumentException("Invalid contribution size");
            var contribution=mapper.readValue(envelope.payload(),SecurePoolMaskedContribution.class);
            var peer=session.manifest.participants().stream().filter(p->p.nodeId().equals(contribution.nodeId())).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("Unregistered participant"));
            if(!SecurePoolCrypto.verify(envelope,peer.publicKey()) || !seen.add(contribution.nodeId())
                    || !session.manifestHash.equals(contribution.manifestHash()) || !transcript.equals(contribution.transcriptHash())
                    || (session.nodeId.equals(contribution.nodeId()) && !envelope.equals(session.contribution)))
                throw new IllegalArgumentException("Invalid masked contribution");
            long[] values=SecurePoolCrypto.decode(contribution.vector(),total.length);
            for(int i=0;i<total.length;i++) total[i]+=values[i];
        }
        var histograms=new LinkedHashMap<String,PrivatePoolingHistogram>();int offset=0;
        for(var field:fields) {
            var counts=new ArrayList<Long>();
            for(int cell=0;cell<field.domain().size();cell++) {
                long value=total[offset++];
                if(value < -1_000_000_000L || value > 1_000_000_000L || (field.domain().structuralZero(cell)&&value!=0))
                    throw new IllegalArgumentException("Invalid aggregate count or incomplete mask cancellation");
                counts.add(value);
            }
            histograms.put(field.id(),new PrivatePoolingHistogram(field.domain(),List.copyOf(counts),
                    field.noiseComponents().stream().mapToDouble(Double::doubleValue).sum(),field.noiseComponents()));
        }
        // Conservatively add costs across all node/dataset versions; do not
        // assume that datasets or sites contain disjoint patients.
        double cost=offers.stream().flatMap(o->o.costs().values().stream()).mapToDouble(Double::doubleValue).sum();
        return new SecurePoolResult("pairwise-masked-v1",session.manifestHash,histograms,cost,offers.stream().map(SecurePoolOffer::nodeId).toList(),
                SecurePoolLayout.unavailable(session.manifest,offers));
    }
}
