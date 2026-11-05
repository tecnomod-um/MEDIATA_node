package org.taniwha.service;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.keytab.Keytab;
import org.apache.kerby.kerberos.kerb.keytab.KeytabEntry;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionKey;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionType;
import org.apache.kerby.kerberos.kerb.type.base.KeyUsage;
import org.apache.kerby.kerberos.kerb.type.base.EncryptedData;
import org.apache.kerby.kerberos.kerb.crypto.EncryptionHandler;
import org.apache.kerby.kerberos.kerb.type.ap.Authenticator;
import org.apache.kerby.kerberos.kerb.type.KerberosTime;
import org.apache.kerby.kerberos.kerb.type.base.PrincipalName;
import org.apache.kerby.kerberos.kerb.type.kdc.EncTgsRepPart;
import org.apache.kerby.kerberos.kerb.type.ticket.EncTicketPart;
import org.apache.kerby.kerberos.kerb.type.ticket.SgtTicket;
import org.apache.kerby.kerberos.kerb.type.ticket.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.DataInputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@Service
public class KerberosService {
    private static final Logger logger = LoggerFactory.getLogger(KerberosService.class);
    private static final int MAX_ENVELOPE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TICKET_BYTES = 1024 * 1024;
    private static final int MAX_PRINCIPAL_BYTES = 512;
    private static final int MAX_AUTHENTICATOR_BYTES = 64 * 1024;
    private static final int MAX_ENCODED_ENVELOPE_CHARS = 4 * ((MAX_ENVELOPE_BYTES + 2) / 3);

    public ServiceTicketEnvelope decodeSgtTicket(String encodedTicket) throws IOException {
        if (encodedTicket == null || encodedTicket.isEmpty()
                || encodedTicket.length() > MAX_ENCODED_ENVELOPE_CHARS) {
            throw new IOException("Kerberos envelope exceeds the permitted size");
        }
        byte[] combinedBytes = Base64.getDecoder().decode(encodedTicket);
        if (combinedBytes.length == 0 || combinedBytes.length > MAX_ENVELOPE_BYTES) {
            throw new IOException("Kerberos envelope exceeds the permitted size");
        }
        try (ByteArrayInputStream byteStream = new ByteArrayInputStream(combinedBytes);
             DataInputStream objStream = new DataInputStream(byteStream)) {

            int ticketLength = readLength(objStream, MAX_TICKET_BYTES, "ticket");
            if (ticketLength == 0) throw new IOException("Missing service ticket");
            byte[] ticketBytes = new byte[ticketLength];
            objStream.readFully(ticketBytes);
            Ticket ticket = new Ticket();
            ticket.decode(ticketBytes);

            // A service envelope must not carry the caller's KDC session key.
            readLength(objStream, 0, "KDC reply");
            EncTgsRepPart encKdcRepPart = new EncTgsRepPart();

            int clientPrincipalLength = readLength(objStream, MAX_PRINCIPAL_BYTES, "client principal");
            if (clientPrincipalLength == 0) throw new IOException("Missing client principal");
            byte[] clientPrincipalBytes = new byte[clientPrincipalLength];
            objStream.readFully(clientPrincipalBytes);
            PrincipalName clientPrincipal = new PrincipalName(new String(clientPrincipalBytes, StandardCharsets.UTF_8));

            SgtTicket sgtTicket = new SgtTicket(ticket, encKdcRepPart);
            sgtTicket.setClientPrincipal(clientPrincipal);
            int encryptionType = objStream.readInt();
            int authenticatorLength = readLength(objStream, MAX_AUTHENTICATOR_BYTES, "authenticator");
            if (authenticatorLength == 0) throw new IOException("Missing authenticator");
            EncryptedData encryptedAuthenticator = new EncryptedData();
            encryptedAuthenticator.setEType(EncryptionType.fromValue(encryptionType));
            byte[] authenticatorBytes = new byte[authenticatorLength];
            objStream.readFully(authenticatorBytes);
            encryptedAuthenticator.setCipher(authenticatorBytes);
            if (objStream.available() != 0) {
                throw new IOException("Unexpected trailing data in Kerberos envelope");
            }
            return new ServiceTicketEnvelope(sgtTicket, encryptedAuthenticator);
        }
    }

    private int readLength(DataInputStream input, int maximum, String field) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum || length > input.available()) {
            throw new IOException("Invalid Kerberos " + field + " length");
        }
        return length;
    }

    public boolean verifyAuthenticator(EncryptedData encryptedAuthenticator, EncryptionKey authenticatedSessionKey,
                                       String authenticatedPrincipal, String authenticatedRealm) {
        try {
            if (encryptedAuthenticator == null) {
                logger.warn("No authenticator found for Kerberos service ticket");
                return false;
            }

            byte[] plaintext = EncryptionHandler.decrypt(
                    encryptedAuthenticator,
                    authenticatedSessionKey,
                    KeyUsage.AP_REQ_AUTH
            );
            logger.debug("Kerberos authenticator decrypted for {}", authenticatedPrincipal);
            Authenticator authenticator = new Authenticator();
            authenticator.decode(plaintext);
            KerberosTime now = KerberosTime.now();
            String authenticatorPrincipal = authenticator.getCname().getName();
            boolean valid = authenticator.getAuthenticatorVno() == 5
                    && authenticatedRealm.equals(authenticator.getCrealm())
                    && authenticatedPrincipal.equals(authenticatorPrincipal)
                    && authenticator.getCtime() != null
                    && Math.abs(now.getTime() - authenticator.getCtime().getTime()) <= 300_000;
            logger.info("Kerberos authenticator principal={} realm={} expected={} valid={}",
                    authenticatorPrincipal, authenticator.getCrealm(), authenticatedPrincipal, valid);
            if (!valid) {
                logger.warn("Kerberos authenticator identity or timestamp validation failed");
            }
            return valid;
        } catch (Exception e) {
            logger.warn("Kerberos authenticator validation failed: {}", e.toString());
            return false;
        }
    }

    public record ServiceTicketEnvelope(SgtTicket ticket, EncryptedData authenticator) {}

    public String ticketFingerprint(SgtTicket ticket) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ticket.getTicket().getEncryptedEncPart().getCipher());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (Exception e) {
            throw new IOException("Unable to fingerprint Kerberos service ticket", e);
        }
    }

    public EncryptionKey loadKeyFromKeytab(String principal, byte[] keytabData, EncryptionType encryptionType) throws KrbException {
        try (ByteArrayInputStream keytabInputStream = new ByteArrayInputStream(keytabData)) {

            Keytab keytab = Keytab.loadKeytab(keytabInputStream);
            PrincipalName principalName = new PrincipalName(principal);
            List<KeytabEntry> keytabEntries = keytab.getKeytabEntries(principalName);

            // Search for the keytab entry with the matching encryption type
            for (KeytabEntry entry : keytabEntries)
                if (entry.getKey().getKeyType() == encryptionType) return entry.getKey();

            logger.error("No matching key found in keytab. Principal: {}, encryption type: {}", principalName, encryptionType);
            return null;
        } catch (IOException e) {
            throw new KrbException("Failed to load keytab", e);
        }
    }

    public EncTicketPart decryptServiceTicket(Ticket serviceTicket, EncryptionKey serviceKey) throws KrbException, IOException {
        byte[] encryptedTicketPart = serviceTicket.getEncryptedEncPart().getCipher();
        byte[] decryptedData = EncryptionHandler.decrypt(encryptedTicketPart, serviceKey, KeyUsage.KDC_REP_TICKET);

        // Decode the decrypted data into an EncTicketPart
        EncTicketPart encTicketPart = new EncTicketPart();
        encTicketPart.decode(decryptedData);
        return encTicketPart;
    }
}
