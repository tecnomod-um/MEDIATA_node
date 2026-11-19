package org.taniwha.privacy.pooling;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.taniwha.util.KeyPairUtil;
import org.taniwha.util.JwtTokenUtil;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import org.springframework.http.MediaType;

class SecurePoolRegistryTest {
    private final JwtTokenUtil tokens=mock(JwtTokenUtil.class);
    private final KeyPairUtil keys=new KeyPairUtil();
    private final List<SecurePoolParticipant> participants=List.of(new SecurePoolParticipant("a","key-a"),new SecurePoolParticipant("b","key-b"));

    @Test void looksUpAnAuthenticatedRosterAndRejectsSubstitutedKeys() {
        var registry=new SecurePoolRegistry(tokens,keys,"https://registry.invalid/taniwha",false);
        var server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(registry,"http")).build();
        when(tokens.generateNodeSyncToken("a","/nodes/secure-pool/roster",keys.getPrivateKey())).thenReturn("scoped");
        server.expect(requestTo("https://registry.invalid/taniwha/nodes/secure-pool/roster"))
                .andExpect(header("Authorization","Bearer scoped"))
                .andRespond(withSuccess("[{\"nodeId\":\"a\",\"publicKey\":\"key-a\"},{\"nodeId\":\"b\",\"publicKey\":\"forged\"}]",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->registry.verify("a",participants)).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test void doesNotSendCredentialsOverHttpWithoutTheLocalDeploymentOptIn() {
        var registry=new SecurePoolRegistry(tokens,keys,"http://registry.invalid/taniwha",false);
        assertThatThrownBy(()->registry.verify("a",participants)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(tokens);
    }

    @Test void isolatedHttpLookupStillRequiresTheExactRegisteredRoster() {
        var registry=new SecurePoolRegistry(tokens,keys,"http://local-stack:8088/taniwha",true);
        var server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(registry,"http")).build();
        when(tokens.generateNodeSyncToken("a","/nodes/secure-pool/roster",keys.getPrivateKey())).thenReturn("scoped");
        server.expect(requestTo("http://local-stack:8088/taniwha/nodes/secure-pool/roster"))
                .andRespond(withSuccess("[{\"nodeId\":\"a\",\"publicKey\":\"key-a\"},{\"nodeId\":\"b\",\"publicKey\":\"key-b\"}]",MediaType.APPLICATION_JSON));
        registry.verify("a",participants);server.verify();
    }
}
