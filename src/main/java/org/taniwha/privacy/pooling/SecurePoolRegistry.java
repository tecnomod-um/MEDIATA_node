package org.taniwha.privacy.pooling;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.taniwha.util.JwtTokenUtil;
import org.taniwha.util.KeyPairUtil;
import java.net.URI;
import java.util.*;

/** Bind peers to registered node identities, not keys supplied by a browser. */
@Component
public class SecurePoolRegistry {
    private final org.springframework.web.client.RestTemplate http;
    private final JwtTokenUtil tokens;
    private final KeyPairUtil keys;
    private final String central;
    private final boolean allowHttp;

    public SecurePoolRegistry(JwtTokenUtil tokens, KeyPairUtil keys,
                              @Value("${host.url}${host.service}") String central,
                              @Value("${discovery.secure-pooling.allow-http-registry:false}") boolean allowHttp) {
        var requests=new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(5000);requests.setReadTimeout(15000);
        // Use the JVM trust store; never bootstrap peer trust from an unverified certificate.
        this.http=new org.springframework.web.client.RestTemplate(requests);
        this.tokens=tokens;this.keys=keys;this.central=central;this.allowHttp=allowHttp;
    }

    public void verify(String ownId,List<SecurePoolParticipant> participants) {
        URI uri=URI.create(central.replaceAll("/+$","")+"/nodes/secure-pool/roster");
        if(!"https".equalsIgnoreCase(uri.getScheme()) && !(allowHttp && "http".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalStateException("Secure pooling needs HTTPS to the registry; isolated local stacks may explicitly permit HTTP");
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(tokens.generateNodeSyncToken(ownId,"/nodes/secure-pool/roster",keys.getPrivateKey()));
        var response=http.exchange(uri,HttpMethod.POST,new HttpEntity<>(Map.of("nodeId",ownId,
                "participantIds",participants.stream().map(SecurePoolParticipant::nodeId).toList()),headers),SecurePoolParticipant[].class);
        if(response.getBody()==null || response.getBody().length!=participants.size()
                || !new HashSet<>(List.of(response.getBody())).equals(new HashSet<>(participants)))
            throw new IllegalArgumentException("Participant keys differ from the registered roster");
    }
}
