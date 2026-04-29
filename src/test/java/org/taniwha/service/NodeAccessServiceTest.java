package org.taniwha.service;

import org.apache.kerby.kerberos.kerb.type.base.EncryptionKey;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionType;
import org.apache.kerby.kerberos.kerb.type.base.PrincipalName;
import org.apache.kerby.kerberos.kerb.type.kdc.EncKdcRepPart;
import org.apache.kerby.kerberos.kerb.type.ticket.EncTicketPart;
import org.apache.kerby.kerberos.kerb.type.ticket.SgtTicket;
import org.apache.kerby.kerberos.kerb.type.ticket.Ticket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.taniwha.model.NodeMetadata;
import org.taniwha.util.JwtTokenUtil;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NodeAccessServiceTest {

    private NodeAccessService service;
    private JwtTokenUtil jwtUtil;
    private KerberosService krbService;
    private FileService fileService;
    private PrincipalService principalService;

    @BeforeEach
    void setUp() {
        jwtUtil = mock(JwtTokenUtil.class);
        krbService = mock(KerberosService.class);
        fileService = mock(FileService.class);
        principalService = mock(PrincipalService.class);

        service = new NodeAccessService(jwtUtil, krbService, fileService, principalService);
        ReflectionTestUtils.setField(service, "expectedRealm", "REALM");
        ReflectionTestUtils.setField(service, "expectedTicketVer", 5);
        ReflectionTestUtils.setField(service, "nodeIp", "http://host.domain");
    }

    @Test
    void getMetadata_delegatesToFileService() {
        NodeMetadata meta = new NodeMetadata();
        when(fileService.parseNodeMetadata()).thenReturn(meta);
        assertThat(service.getMetadata()).isSameAs(meta);
    }

    @Test
    void getRawMetadata_delegatesToFileService() {
        when(fileService.getRawNodeMetadata()).thenReturn("raw metadata");
        assertThat(service.getRawMetadata()).isEqualTo("raw metadata");
    }

    @Test
    void getHostName_variousUris() {
        assertThat(service.getHostName("plain")).isEqualTo("plain");
        assertThat(service.getHostName("http://example.com/path"))
                .isEqualTo("HTTP/example.com");
        assertThat(service.getHostName("https://test.org"))
                .isEqualTo("HTTPS/test.org");
    }

    @Test
    void verifySgtTicket_versionMismatch_returnsFalse() {
        SgtTicket sgt = mock(SgtTicket.class);
        Ticket t = mock(Ticket.class);
        when(sgt.getTicket()).thenReturn(t);
        when(t.getTktvno()).thenReturn(42);
        assertThat(service.verifySgtTicket(sgt)).isFalse();
    }

    @Test
    void verifySgtTicket_realmMismatch_returnsFalse() {
        SgtTicket sgt = mock(SgtTicket.class);
        Ticket t = mock(Ticket.class);
        when(sgt.getTicket()).thenReturn(t);
        when(t.getTktvno()).thenReturn(5);
        when(t.getRealm()).thenReturn("OTHER");
        assertThat(service.verifySgtTicket(sgt)).isFalse();
    }

    @Test
    void verifySgtTicket_snameMismatch_returnsFalse() {
        SgtTicket sgt = mock(SgtTicket.class);
        Ticket t = mock(Ticket.class);
        PrincipalName wrong = mock(PrincipalName.class);
        when(wrong.getName()).thenReturn("WRONG");
        when(sgt.getTicket()).thenReturn(t);
        when(sgt.getEncKdcRepPart()).thenReturn(mock(EncKdcRepPart.class));
        when(t.getTktvno()).thenReturn(5);
        when(t.getRealm()).thenReturn("REALM");
        when(t.getSname()).thenReturn(wrong);
        assertThat(service.verifySgtTicket(sgt)).isFalse();
    }

    @Test
    void verifySgtTicket_nullSessionKey_returnsFalse() {
        SgtTicket sgt = mock(SgtTicket.class);
        Ticket t = mock(Ticket.class);
        EncKdcRepPart part = mock(EncKdcRepPart.class);
        PrincipalName pn = mock(PrincipalName.class);
        when(pn.getName()).thenReturn("HTTP/host.domain");
        when(sgt.getTicket()).thenReturn(t);
        when(sgt.getEncKdcRepPart()).thenReturn(part);
        when(t.getTktvno()).thenReturn(5);
        when(t.getRealm()).thenReturn("REALM");
        when(t.getSname()).thenReturn(pn);
        when(part.getKey()).thenReturn(null);
        assertThat(service.verifySgtTicket(sgt)).isFalse();
    }

    @Test
    void verifySgtTicket_successfulFlow_returnsTrue() throws Exception {
        SgtTicket sgt = mock(SgtTicket.class);
        Ticket t = mock(Ticket.class);
        EncKdcRepPart part = mock(EncKdcRepPart.class);
        PrincipalName pn = mock(PrincipalName.class);

        when(pn.getName()).thenReturn("HTTP/host.domain");
        when(sgt.getTicket()).thenReturn(t);
        when(sgt.getEncKdcRepPart()).thenReturn(part);

        when(t.getTktvno()).thenReturn(5);
        when(t.getRealm()).thenReturn("REALM");
        when(t.getSname()).thenReturn(pn);
        when(part.getFlags()).thenReturn(new org.apache.kerby.kerberos.kerb.type.ticket.TicketFlags());
        when(part.getAuthTime()).thenReturn(null);
        when(part.getEndTime()).thenReturn(null);
        when(part.getRenewTill()).thenReturn(null);

        EncryptionKey key = mock(EncryptionKey.class);
        byte[] keyData = new byte[]{1, 2, 3};
        when(part.getKey()).thenReturn(key);
        when(key.getKeyData()).thenReturn(keyData);
        when(key.getKeyType()).thenReturn(EncryptionType.AES128_CTS_HMAC_SHA1_96);

        when(principalService.getKeytab()).thenReturn(new byte[]{0});
        when(krbService.loadKeyFromKeytab(anyString(), any(), eq(EncryptionType.AES128_CTS_HMAC_SHA1_96)))
                .thenReturn(key);
        EncTicketPart encPart = mock(EncTicketPart.class);
        when(encPart.getKey()).thenReturn(key);
        when(krbService.decryptServiceTicket(t, key)).thenReturn(encPart);

        assertThat(service.verifySgtTicket(sgt)).isTrue();
    }

    @Test
    void verifyKerberosToken_decodeError_throwsIOException() throws IOException {
        when(krbService.decodeSgtTicket("bad")).thenThrow(new IOException("oops"));
        assertThatThrownBy(() -> service.verifyKerberosToken("bad"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("oops");
    }

    @Test
    void verifyKerberosToken_success_callsStoreAndReturnsTrue() throws Exception {

        SgtTicket sgt = mock(SgtTicket.class);
        when(krbService.decodeSgtTicket("good")).thenReturn(sgt);
        PrincipalName pn = mock(PrincipalName.class);
        when(sgt.getClientPrincipal()).thenReturn(pn);
        when(pn.getName()).thenReturn("alice@REALM");

        NodeAccessService spy = spy(service);
        doReturn(true).when(spy).verifySgtTicket(sgt);

        assertThat(spy.verifyKerberosToken("good")).isTrue();
        verify(spy).storeToken(sgt);
    }

    @Test
    void validateUserToken_noStored_returnsFalse() {
        when(jwtUtil.getUsernameFromToken("jwt")).thenReturn("user");
        assertThat(service.validateUserToken("jwt", "sgt")).isFalse();
    }

    @Test
    void validateUserToken_withStoredAndValid_returnsTrue() throws Exception {
        SgtTicket sgt = mock(SgtTicket.class);
        PrincipalName pn = mock(PrincipalName.class);
        when(pn.getName()).thenReturn("user@REALM");
        when(sgt.getClientPrincipal()).thenReturn(pn);
        service.storeToken(sgt);

        when(jwtUtil.getUsernameFromToken("jwt")).thenReturn("user");
        when(krbService.decodeSgtTicket("sgt")).thenReturn(sgt);

        NodeAccessService spy = spy(service);
        doReturn(true).when(spy).verifySgtTicket(sgt);

        assertThat(spy.validateUserToken("jwt", "sgt")).isTrue();
    }
}
