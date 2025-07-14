package org.taniwha.service;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.crypto.EncryptionHandler;
import org.apache.kerby.kerberos.kerb.type.base.EncryptedData;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionType;
import org.apache.kerby.kerberos.kerb.type.base.KeyUsage;
import org.apache.kerby.kerberos.kerb.type.ticket.Ticket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        String invalid = "not-base64!";
        assertThrows(IllegalArgumentException.class, () -> service.decodeSgtTicket(invalid));
    }

    @Test
    void loadKeyFromKeytab_withMalformedData_throwsKrbException() {
        byte[] bogus = new byte[]{0x01, 0x02, 0x03};
        assertThrows(KrbException.class, () ->
                service.loadKeyFromKeytab("principal@REALM", bogus, EncryptionType.AES256_CTS_HMAC_SHA1_96));
    }

    @Test
    void decryptServiceTicket_whenDecryptFails_throwsKrbException() {
        Ticket fakeTicket = mock(Ticket.class);
        EncryptedData fakeEnc = mock(EncryptedData.class);
        byte[] cipherBytes = new byte[]{0x0a, 0x0b, 0x0c};
        when(fakeEnc.getCipher()).thenReturn(cipherBytes);
        when(fakeTicket.getEncryptedEncPart()).thenReturn(fakeEnc);

        try (MockedStatic<EncryptionHandler> enc = Mockito.mockStatic(EncryptionHandler.class)) {
            enc.when(() ->
                    EncryptionHandler.decrypt(cipherBytes, /*serviceKey=*/ null, KeyUsage.KDC_REP_TICKET)
            ).thenThrow(new KrbException("decrypt error"));

            KrbException ex = assertThrows(KrbException.class, () ->
                    service.decryptServiceTicket(fakeTicket, /*serviceKey=*/ null)
            );
            assertEquals("decrypt error", ex.getMessage());
        }
    }
}
