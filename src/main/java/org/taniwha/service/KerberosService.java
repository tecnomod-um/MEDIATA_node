package org.taniwha.service;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.crypto.EncryptionHandler;
import org.apache.kerby.kerberos.kerb.keytab.Keytab;
import org.apache.kerby.kerberos.kerb.keytab.KeytabEntry;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionKey;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionType;
import org.apache.kerby.kerberos.kerb.type.base.KeyUsage;
import org.apache.kerby.kerberos.kerb.type.base.PrincipalName;
import org.apache.kerby.kerberos.kerb.type.kdc.EncTgsRepPart;
import org.apache.kerby.kerberos.kerb.type.ticket.EncTicketPart;
import org.apache.kerby.kerberos.kerb.type.ticket.KrbTicket;
import org.apache.kerby.kerberos.kerb.type.ticket.SgtTicket;
import org.apache.kerby.kerberos.kerb.type.ticket.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Base64;
import java.util.List;

@Service
public class KerberosService {
    private static final Logger logger = LoggerFactory.getLogger(KerberosService.class);

    public KrbTicket decodeSgtTicket(String encodedTicket) throws IOException {
        byte[] combinedBytes = Base64.getDecoder().decode(encodedTicket);
        try (ByteArrayInputStream byteStream = new ByteArrayInputStream(combinedBytes);
             ObjectInputStream objStream = new ObjectInputStream(byteStream)) {

            int ticketLength = objStream.readInt();
            byte[] ticketBytes = new byte[ticketLength];
            objStream.readFully(ticketBytes);
            Ticket ticket = new Ticket();
            ticket.decode(ticketBytes);

            int encKdcRepPartLength = objStream.readInt();
            byte[] encKdcRepPartBytes = new byte[encKdcRepPartLength];
            objStream.readFully(encKdcRepPartBytes);
            EncTgsRepPart encKdcRepPart = new EncTgsRepPart();
            encKdcRepPart.decode(encKdcRepPartBytes);

            int clientPrincipalLength = objStream.readInt();
            byte[] clientPrincipalBytes = new byte[clientPrincipalLength];
            objStream.readFully(clientPrincipalBytes);
            PrincipalName clientPrincipal = new PrincipalName(new String(clientPrincipalBytes));

            SgtTicket sgtTicket = new SgtTicket(ticket, encKdcRepPart);
            sgtTicket.setClientPrincipal(clientPrincipal);
            return sgtTicket;
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
