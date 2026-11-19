package org.taniwha.privacy.pooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.taniwha.util.KeyPairUtil;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecurePoolServiceTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
    private final ObjectMapper mapper=new ObjectMapper();
    private final List<KeyPairUtil> keys=List.of(new KeyPairUtil(),new KeyPairUtil(),new KeyPairUtil());
    private final List<String> ids=List.of("alpha","beta","gamma");
    private final PrivatePoolingDomain domain=new PrivatePoolingDomain(List.of(-.5,5.,10.5),null,List.of(0.,10.),"continuous",false,true);

    @Test void masksCancelOnlyInTheCompleteSignedRoundAndRetriesReuseTheContribution() throws Exception {
        var services=services();var manifest=manifest("continuous");String json=mapper.writeValueAsString(manifest);
        var offers=prepare(services,json);
        assertThat(offers).allSatisfy(offer->assertThat(offer.payload()).doesNotContain("\"counts\"","privateKey"));
        var round=new SecurePoolRoundRequest(manifest.sessionId(),offers);
        var contributions=new ArrayList<SecurePoolEnvelope>();
        for(var service:services) {
            var contribution=service.mask(round);contributions.add(contribution);
            assertThat(service.mask(round)).isEqualTo(contribution);
            var wire=mapper.readValue(contribution.payload(),SecurePoolMaskedContribution.class);
            assertThat(Arrays.equals(SecurePoolCrypto.decode(wire.vector(),3),new long[]{1,20,0})).isFalse();
        }
        var finish=new SecurePoolFinishRequest(manifest.sessionId(),offers,contributions);
        var result=services.get(0).finish(finish);
        assertThat(result.features().get("reading").counts()).containsExactly(6L,60L,0L);
        assertThat(result.features().get("reading").noiseComponents()).containsExactly(4.,4.,4.);
        assertThat(result.rowEpsilon()).isEqualTo(3*Math.log(2));
        assertThat(services.get(1).finish(finish)).isEqualTo(result);
        assertThat(result.protocol()).isEqualTo("pairwise-masked-v1");
        assertThat(result.unavailable()).isEmpty();
    }

    @Test void rejectsDropoutDuplicatesTamperingAndCrossSessionTranscripts() throws Exception {
        var services=services();var manifest=manifest("continuous");var offers=prepare(services,mapper.writeValueAsString(manifest));
        assertThatThrownBy(()->services.get(0).mask(new SecurePoolRoundRequest(manifest.sessionId(),offers.subList(0,2))))
                .isInstanceOf(IllegalArgumentException.class);
        var altered=new ArrayList<>(offers);altered.set(1,new SecurePoolEnvelope(offers.get(1).payload().replace("beta","other"),offers.get(1).signature()));
        assertThatThrownBy(()->services.get(0).mask(new SecurePoolRoundRequest(manifest.sessionId(),altered))).isInstanceOf(Exception.class);
        var packets=new ArrayList<SecurePoolEnvelope>();
        for(var service:services) packets.add(service.mask(new SecurePoolRoundRequest(manifest.sessionId(),offers)));
        assertThatThrownBy(()->services.get(0).finish(new SecurePoolFinishRequest(manifest.sessionId(),offers,packets.subList(0,2))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->services.get(0).finish(new SecurePoolFinishRequest(manifest.sessionId(),offers,List.of(packets.get(0),packets.get(0),packets.get(2)))))
                .isInstanceOf(IllegalArgumentException.class);
        var tampered=new ArrayList<>(packets);
        var original=mapper.readValue(packets.get(1).payload(),SecurePoolMaskedContribution.class);
        tampered.set(1,new SecurePoolEnvelope(mapper.writeValueAsString(new SecurePoolMaskedContribution(original.manifestHash(),
                original.transcriptHash(),original.nodeId(),SecurePoolCrypto.encode(new long[]{0,0,0}))),packets.get(1).signature()));
        assertThatThrownBy(()->services.get(0).finish(new SecurePoolFinishRequest(manifest.sessionId(),offers,tampered)))
                .isInstanceOf(IllegalArgumentException.class);
        var second=manifest("continuous");var secondOffers=prepare(services,mapper.writeValueAsString(second));
        services.get(0).mask(new SecurePoolRoundRequest(second.sessionId(),secondOffers));
        assertThatThrownBy(()->services.get(0).finish(new SecurePoolFinishRequest(second.sessionId(),secondOffers,packets)))
                .isInstanceOf(IllegalArgumentException.class);
        String changed=mapper.writeValueAsString(new SecurePoolManifest(manifest.sessionId(),manifest.createdAt()+1,
                manifest.participants(),manifest.sources(),manifest.features()));
        assertThatThrownBy(()->services.get(0).prepare(new SecurePoolPrepareRequest(changed,"alpha"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void pointScoresCanBecomeCategoriesWithoutReleasingNewInputs() throws Exception {
        var services=services();var manifest=manifest("categorical");var offers=prepare(services,mapper.writeValueAsString(manifest));
        var packets=new ArrayList<SecurePoolEnvelope>();
        for(var service:services) packets.add(service.mask(new SecurePoolRoundRequest(manifest.sessionId(),offers)));
        var result=services.get(0).finish(new SecurePoolFinishRequest(manifest.sessionId(),offers,packets));
        assertThat(result.unavailable()).isEmpty();
        assertThat(result.features().get("reading").domain().categories()).containsExactly("0", "10");
        assertThat(result.features().get("reading").counts()).containsExactly(6L,60L,0L,0L);
        assertThat(result.rowEpsilon()).isEqualTo(3*Math.log(2));
    }

    @Test void registeredIdentityChecksPrecedeAnyReadAndDiscardedSessionsCannotFinish() throws Exception {
        var input=mock(SecurePoolInputs.class);var registry=mock(SecurePoolRegistry.class);
        doThrow(new IllegalArgumentException("unregistered peer")).when(registry).verify(anyString(),anyList());
        var service=new SecurePoolService(input,registry,keys.get(0),mapper,new SecurePoolRebinCache(root.toString(),mapper));var manifest=manifest("continuous");
        assertThatThrownBy(()->service.prepare(new SecurePoolPrepareRequest(mapper.writeValueAsString(manifest),"alpha")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(input);
        var services=services();var offers=prepare(services,mapper.writeValueAsString(manifest));
        services.get(0).discard(manifest.sessionId());
        assertThatThrownBy(()->services.get(0).mask(new SecurePoolRoundRequest(manifest.sessionId(),offers))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void allRoundsSurviveHttpJsonSerializationWithoutExposingNodeHistograms() throws Exception {
        var services=services();var manifest=manifest("continuous");var mvc=new ArrayList<org.springframework.test.web.servlet.MockMvc>();
        for(var service:services) mvc.add(org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new org.taniwha.controller.SecurePoolingController(service)).build());
        var offers=new ArrayList<SecurePoolEnvelope>();
        for(int i=0;i<mvc.size();i++) {
            var response=mvc.get(i).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/data/securePool/prepare")
                            .contentType("application/json").content(mapper.writeValueAsString(new SecurePoolPrepareRequest(mapper.writeValueAsString(manifest),ids.get(i)))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"))
                    .andReturn().getResponse().getContentAsString();
            var offer=mapper.readValue(response,SecurePoolEnvelope.class);
            assertThat(offer.payload()).doesNotContain("\"counts\"");offers.add(offer);
        }
        var contributions=new ArrayList<SecurePoolEnvelope>();
        for(var node:mvc) {
            String response=node.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/data/securePool/mask")
                            .contentType("application/json").content(mapper.writeValueAsString(new SecurePoolRoundRequest(manifest.sessionId(),offers))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andReturn().getResponse().getContentAsString();
            contributions.add(mapper.readValue(response,SecurePoolEnvelope.class));
        }
        mvc.get(0).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/data/securePool/finish")
                        .contentType("application/json").content(mapper.writeValueAsString(new SecurePoolFinishRequest(manifest.sessionId(),offers,contributions))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.features.reading.counts[0]").value(6))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.protocol").value("pairwise-masked-v1"));
    }

    @Test void aliasesOfOneCachedVariableCannotPretendToBeIndependentContributions() throws Exception {
        var services=services();var original=manifest("continuous");
        var sources=new ArrayList<>(original.sources());sources.add(new SecurePoolSource("alpha","alias.csv",Map.of()));
        var members=new ArrayList<>(original.features().get(0).members());members.add(new SecurePoolMember("alpha","alias.csv","Value"));
        var manifest=new SecurePoolManifest(original.sessionId(),original.createdAt(),original.participants(),sources,
                List.of(new SecurePoolFeature("reading","continuous",members)));
        var offers=prepare(services,mapper.writeValueAsString(manifest));var packets=new ArrayList<SecurePoolEnvelope>();
        for(var service:services) packets.add(service.mask(new SecurePoolRoundRequest(manifest.sessionId(),offers)));
        var result=services.get(0).finish(new SecurePoolFinishRequest(manifest.sessionId(),offers,packets));
        assertThat(result.features()).isEmpty();
        assertThat(result.unavailable().get("reading")).contains("same dataset version");
    }

    @Test void registrationChangesDoNotCreateFreshPaddingForTheSameLocalInputs() throws Exception {
        var services=services();var first=manifest("continuous");
        var original=complete(services,first,ids);
        long before;
        try(var files=java.nio.file.Files.walk(root)) {before=files.filter(p->p.toString().endsWith(".json")).count();}
        var renamed=ids.stream().map(id->id+"-restart").toList();
        var restarted=new SecurePoolManifest(UUID.randomUUID().toString(),System.currentTimeMillis(),
                java.util.stream.IntStream.range(0,3).mapToObj(i->new SecurePoolParticipant(renamed.get(i),keys.get(i).getPublicKeyBase64())).toList(),
                renamed.stream().map(id->new SecurePoolSource(id,"input.csv",Map.of())).toList(),
                List.of(new SecurePoolFeature("reading","continuous",renamed.stream().map(id->new SecurePoolMember(id,"input.csv","Value")).toList())));
        assertThat(complete(services,restarted,renamed).features()).isEqualTo(original.features());
        try(var files=java.nio.file.Files.walk(root)) {
            assertThat(files.filter(p->p.toString().endsWith(".json")).count()).isEqualTo(before);
        }
    }

    private SecurePoolResult complete(List<SecurePoolService> services,SecurePoolManifest manifest,List<String> nodeIds) throws Exception {
        var offers=new ArrayList<SecurePoolEnvelope>();
        for(int i=0;i<services.size();i++)offers.add(services.get(i).prepare(new SecurePoolPrepareRequest(mapper.writeValueAsString(manifest),nodeIds.get(i))));
        var packets=new ArrayList<SecurePoolEnvelope>();
        for(var service:services)packets.add(service.mask(new SecurePoolRoundRequest(manifest.sessionId(),offers)));
        return services.get(0).finish(new SecurePoolFinishRequest(manifest.sessionId(),offers,packets));
    }

    private List<SecurePoolService> services() throws Exception {
        var result=new ArrayList<SecurePoolService>();
        for(int i=0;i<ids.size();i++) {
            var inputs=mock(SecurePoolInputs.class);
            when(inputs.versionedSource(any())).thenReturn(new VersionedPoolingRelease("version",
                    new PrivatePoolingRelease(4,"A",Math.log(2),null,
                    Map.of("Value",new PrivatePoolingHistogram(domain,List.of((long)i+1,20L,0L),4.)))));
            result.add(new SecurePoolService(inputs,mock(SecurePoolRegistry.class),keys.get(i),mapper,new SecurePoolRebinCache(root.resolve(ids.get(i)).toString(),mapper)));
        }
        return result;
    }

    private SecurePoolManifest manifest(String type) {
        return new SecurePoolManifest(UUID.randomUUID().toString(),System.currentTimeMillis(),
                java.util.stream.IntStream.range(0,3).mapToObj(i->new SecurePoolParticipant(ids.get(i),keys.get(i).getPublicKeyBase64())).toList(),
                ids.stream().map(id->new SecurePoolSource(id,"input.csv",Map.of())).toList(),
                List.of(new SecurePoolFeature("reading",type,ids.stream().map(id->new SecurePoolMember(id,"input.csv","Value")).toList())));
    }

    private List<SecurePoolEnvelope> prepare(List<SecurePoolService> services,String manifest) throws Exception {
        var result=new ArrayList<SecurePoolEnvelope>();
        for(int i=0;i<services.size();i++) result.add(services.get(i).prepare(new SecurePoolPrepareRequest(manifest,ids.get(i))));
        return result;
    }
}
