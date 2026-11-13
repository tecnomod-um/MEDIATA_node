package org.taniwha.service;

import org.apache.kerby.kerberos.kerb.type.ticket.Ticket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.taniwha.util.JwtTokenUtil;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class KerberosExchangeSecurityTest {
    private KerberosService kerberos;
    private NodeAccessService node;
    private JwtTokenUtil jwt;
    private String token;
    private String alice;

    @BeforeEach
    void setup() throws Exception {
        kerberos = KerberosTicketFixture.kerberos();
        jwt = KerberosTicketFixture.jwt();
        node = KerberosTicketFixture.node(kerberos, jwt);
        token = KerberosTicketFixture.envelope(
                KerberosTicketFixture.ticket(System.currentTimeMillis() + 60000), "alice@REALM", "alice");
        alice = KerberosTicketFixture.userToken(jwt, "alice");
    }

    @Test
    void fullExchangeRejectsBothRepeatedValidationAndReauthorization() throws Exception {
        assertTrue(node.verifyKerberosToken(token));
        assertTrue(node.validateUserToken(alice, token));
        assertFalse(node.validateUserToken(alice, token));
        assertFalse(node.verifyKerberosToken(token));
        assertFalse(node.validateUserToken(alice, token));
    }

    @Test
    void wrongJwtIdentityDoesNotConsumeTheCorrectUsersExchange() throws Exception {
        assertTrue(node.verifyKerberosToken(token));
        assertFalse(node.validateUserToken(KerberosTicketFixture.userToken(jwt, "mallory"), token));
        assertTrue(node.validateUserToken(alice, token));
    }

    @Test
    void encryptedClientCannotBeReplacedByCallerSuppliedIdentity() throws Exception {
        Ticket ticket = KerberosTicketFixture.ticket(System.currentTimeMillis() + 60000);
        assertFalse(node.verifyKerberosToken(KerberosTicketFixture.envelope(ticket, "mallory@REALM", "mallory")));
        assertFalse(node.verifyKerberosToken(KerberosTicketFixture.envelope(ticket, "alice@OTHER", "alice")));
        assertFalse(node.verifyKerberosToken(KerberosTicketFixture.envelope(ticket, "alice@REALM", "mallory")));
    }

    @Test
    void expiredEncryptedTicketAndWrongServiceAreRejected() throws Exception {
        Ticket expired = KerberosTicketFixture.ticket(System.currentTimeMillis() - 1000);
        assertFalse(node.verifyKerberosToken(KerberosTicketFixture.envelope(expired, "alice@REALM", "alice")));
        Ticket wrongService = KerberosTicketFixture.ticket(System.currentTimeMillis() + 60000);
        wrongService.setSname(new org.apache.kerby.kerberos.kerb.type.base.PrincipalName("HTTP/other-node"));
        assertFalse(node.verifyKerberosToken(KerberosTicketFixture.envelope(wrongService, "alice@REALM", "alice")));
    }

    @Test
    void invalidTicketsAndMalformedEnvelopesDoNotRetainAuthorizationState() throws Exception {
        for (int i = 1; i <= 32; i++) {
            Ticket ticket = KerberosTicketFixture.ticket(System.currentTimeMillis() + 60000);
            byte[] cipher = ticket.getEncryptedEncPart().getCipher();
            cipher[0] ^= 1;
            ticket.getEncryptedEncPart().setCipher(cipher);
            assertFalse(node.verifyKerberosToken(KerberosTicketFixture.envelope(ticket, "alice@REALM", "alice")));
        }
        KerberosAuthorizationStore store = (KerberosAuthorizationStore)
                ReflectionTestUtils.getField(node, "authorizations");
        assertEquals(0, store.size());
        assertThrows(IOException.class, () -> kerberos.decodeSgtTicket("A".repeat(3_000_000)));
        byte[] trailing = Arrays.copyOf(Base64.getDecoder().decode(token), Base64.getDecoder().decode(token).length + 1);
        assertThrows(IOException.class, () -> kerberos.decodeSgtTicket(Base64.getEncoder().encodeToString(trailing)));
    }

    @Test
    void decodingAnotherEnvelopeCannotReplaceItsRequestsAuthenticator() throws Exception {
        var valid = kerberos.decodeSgtTicket(token);
        var invalid = kerberos.decodeSgtTicket(KerberosTicketFixture.envelope(valid.ticket().getTicket(), "alice@REALM", "mallory"));
        assertTrue(kerberos.verifyAuthenticator(valid.authenticator(), KerberosTicketFixture.SESSION_KEY, "alice", "REALM"));
        assertFalse(kerberos.verifyAuthenticator(invalid.authenticator(), KerberosTicketFixture.SESSION_KEY, "alice", "REALM"));
    }

    @Test
    void multipleSessionsForTheSameUserDoNotOverwriteEachOther() throws Exception {
        String second = KerberosTicketFixture.envelope(
                KerberosTicketFixture.ticket(System.currentTimeMillis() + 60000), "alice@REALM", "alice");
        assertTrue(node.verifyKerberosToken(token));
        assertTrue(node.verifyKerberosToken(second));
        assertTrue(node.validateUserToken(alice, token));
        assertTrue(node.validateUserToken(alice, second));
    }

    @Test
    void concurrentFullExchangesCannotReopenConsumedAuthorization() throws Exception {
        assertTrue(node.verifyKerberosToken(token));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> validations = new ArrayList<>();
            List<Future<Boolean>> authorizations = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                authorizations.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return node.verifyKerberosToken(token);
                }));
                validations.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return node.validateUserToken(alice, token);
                }));
            }
            start.countDown();
            int successfulValidations = 0;
            for (Future<Boolean> result : validations) {
                if (result.get(10, TimeUnit.SECONDS)) successfulValidations++;
            }
            for (Future<Boolean> result : authorizations) assertFalse(result.get(10, TimeUnit.SECONDS));
            assertEquals(1, successfulValidations);
            assertFalse(node.verifyKerberosToken(token));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
