package org.taniwha.service;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.crypto.EncryptionHandler;
import org.apache.kerby.kerberos.kerb.keytab.Keytab;
import org.apache.kerby.kerberos.kerb.keytab.KeytabEntry;
import org.apache.kerby.kerberos.kerb.type.base.EncryptedData;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionKey;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionType;
import org.apache.kerby.kerberos.kerb.type.base.KeyUsage;
import org.apache.kerby.kerberos.kerb.type.ticket.Ticket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KerberosServiceTest {

    private KerberosService service;

    @BeforeEach
    void setUp() {
        service = new KerberosService();
    }

    @Test
    void decodeSgtTicket_withInvalidBase64_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.decodeSgtTicket("not-base64!"));
    }

    @Test
    void loadKeyFromKeytab_withMalformedData_throwsKrbException() {
        byte[] malformedKeytab = new byte[]{0x01, 0x02, 0x03};
        assertThrows(KrbException.class, () ->
                service.loadKeyFromKeytab("principal@REALM", malformedKeytab, EncryptionType.AES256_CTS_HMAC_SHA1_96));
    }

    @Test
    void loadKeyFromKeytab_withNoMatchingType_returnsNull() throws Exception {
        byte[] keytabData = new byte[]{0x00};
        Keytab keytab = mock(Keytab.class);
        KeytabEntry entry = mock(KeytabEntry.class);
        EncryptionKey key = mock(EncryptionKey.class);

        when(entry.getKey()).thenReturn(key);
        when(key.getKeyType()).thenReturn(EncryptionType.AES128_CTS_HMAC_SHA1_96);
        when(keytab.getKeytabEntries(any())).thenReturn(List.of(entry));

        try (MockedStatic<Keytab> mockedKeytab = Mockito.mockStatic(Keytab.class)) {
            mockedKeytab.when(() -> Keytab.loadKeytab(any(java.io.InputStream.class))).thenReturn(keytab);

            EncryptionKey loaded = service.loadKeyFromKeytab(
                    "principal@REALM",
                    keytabData,
                    EncryptionType.AES256_CTS_HMAC_SHA1_96
            );

            assertNull(loaded);
        }
    }

    @Test
    void loadKeyFromKeytab_withMatchingType_returnsKey() throws Exception {
        byte[] keytabData = new byte[]{0x00};
        Keytab keytab = mock(Keytab.class);
        KeytabEntry entry = mock(KeytabEntry.class);
        EncryptionKey key = mock(EncryptionKey.class);

        when(entry.getKey()).thenReturn(key);
        when(key.getKeyType()).thenReturn(EncryptionType.AES256_CTS_HMAC_SHA1_96);
        when(keytab.getKeytabEntries(any())).thenReturn(List.of(entry));

        try (MockedStatic<Keytab> mockedKeytab = Mockito.mockStatic(Keytab.class)) {
            mockedKeytab.when(() -> Keytab.loadKeytab(any(java.io.InputStream.class))).thenReturn(keytab);

            EncryptionKey loaded = service.loadKeyFromKeytab(
                    "principal@REALM",
                    keytabData,
                    EncryptionType.AES256_CTS_HMAC_SHA1_96
            );

            assertSame(key, loaded);
        }
    }

    @Test
    void decryptServiceTicket_whenDecryptFails_throwsKrbException() {
        Ticket ticket = mock(Ticket.class);
        EncryptedData encryptedData = mock(EncryptedData.class);
        byte[] cipherBytes = new byte[]{0x0a, 0x0b, 0x0c};
        when(encryptedData.getCipher()).thenReturn(cipherBytes);
        when(ticket.getEncryptedEncPart()).thenReturn(encryptedData);

        try (MockedStatic<EncryptionHandler> encryptionHandler = Mockito.mockStatic(EncryptionHandler.class)) {
            encryptionHandler.when(() ->
                    EncryptionHandler.decrypt(cipherBytes, null, KeyUsage.KDC_REP_TICKET)
            ).thenThrow(new KrbException("decrypt error"));

            KrbException exception = assertThrows(KrbException.class, () -> service.decryptServiceTicket(ticket, null));
            assertEquals("decrypt error", exception.getMessage());
        }
    }
}
