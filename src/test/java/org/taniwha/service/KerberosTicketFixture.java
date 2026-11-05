package org.taniwha.service;

import org.apache.kerby.kerberos.kerb.crypto.EncryptionHandler;
import org.apache.kerby.kerberos.kerb.type.KerberosTime;
import org.apache.kerby.kerberos.kerb.type.ap.Authenticator;
import org.apache.kerby.kerberos.kerb.type.base.*;
import org.apache.kerby.kerberos.kerb.type.ticket.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.taniwha.util.JwtTokenUtil;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

final class KerberosTicketFixture {
    static final EncryptionKey SERVICE_KEY = new EncryptionKey(
            EncryptionType.AES128_CTS_HMAC_SHA1_96, new byte[16]);
    static final EncryptionKey SESSION_KEY = new EncryptionKey(
            EncryptionType.AES128_CTS_HMAC_SHA1_96,
            new byte[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1});

    static KerberosService kerberos() {
        // The fixture supplies a service key; ticket decryption and validation are real.
        return new KerberosService() {
            @Override
            public EncryptionKey loadKeyFromKeytab(String principal, byte[] keytab, EncryptionType type) {
                return SERVICE_KEY;
            }
        };
    }

    static JwtTokenUtil jwt() {
        JwtTokenUtil jwt = new JwtTokenUtil();
        ReflectionTestUtils.setField(jwt, "secret", "test-only-kerberos-exchange-key-with-at-least-32-bytes");
        ReflectionTestUtils.setField(jwt, "expiration", 3600L);
        return jwt;
    }

    static String userToken(JwtTokenUtil jwt, String user) {
        return jwt.generateToken(user, Map.of("token_use", "central_user",
                "iss", "mediata-orchestrator", "aud", "mediata-central"));
    }

    static NodeAccessService node(KerberosService kerberos, JwtTokenUtil jwt) {
        PrincipalService principal = new PrincipalService();
        principal.setKeytab("AA==");
        NodeAccessService node = new NodeAccessService(jwt, kerberos, null, principal);
        ReflectionTestUtils.setField(node, "expectedRealm", "REALM");
        ReflectionTestUtils.setField(node, "expectedTicketVer", 5);
        ReflectionTestUtils.setField(node, "nodeIp", "http://node.example");
        return node;
    }

    static Ticket ticket(long expiresAt) throws Exception {
        EncTicketPart part = new EncTicketPart();
        part.setFlags(new TicketFlags());
        part.setKey(SESSION_KEY);
        part.setCrealm("REALM");
        part.setCname(new PrincipalName("alice"));
        TransitedEncoding transit = new TransitedEncoding();
        transit.setTrType(TransitedEncodingType.DOMAIN_X500_COMPRESS);
        transit.setContents(new byte[0]);
        part.setTransited(transit);
        part.setAuthTime(new KerberosTime(System.currentTimeMillis() - 10000));
        part.setEndTime(new KerberosTime(expiresAt));
        Ticket ticket = new Ticket();
        ticket.setTktKvno(5);
        ticket.setRealm("REALM");
        ticket.setSname(new PrincipalName("HTTP/node.example"));
        ticket.setEncryptedEncPart(EncryptionHandler.encrypt(part.encode(), SERVICE_KEY, KeyUsage.KDC_REP_TICKET));
        return ticket;
    }

    static String envelope(Ticket ticket, String outerPrincipal, String authenticatorPrincipal) throws Exception {
        Authenticator auth = new Authenticator();
        auth.setAuthenticatorVno(5);
        auth.setCrealm("REALM");
        auth.setCname(new PrincipalName(authenticatorPrincipal));
        auth.setCtime(KerberosTime.now());
        auth.setCusec(0);
        EncryptedData encrypted = EncryptionHandler.encrypt(auth.encode(), SESSION_KEY, KeyUsage.AP_REQ_AUTH);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            byte[] ticketBytes = ticket.encode();
            out.writeInt(ticketBytes.length);
            out.write(ticketBytes);
            out.writeInt(0);
            byte[] principal = outerPrincipal.getBytes(StandardCharsets.UTF_8);
            out.writeInt(principal.length);
            out.write(principal);
            out.writeInt(encrypted.getEType().getValue());
            out.writeInt(encrypted.getCipher().length);
            out.write(encrypted.getCipher());
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
