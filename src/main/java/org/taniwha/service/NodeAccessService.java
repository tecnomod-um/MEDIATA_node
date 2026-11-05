package org.taniwha.service;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.type.KerberosTime;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionKey;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionType;
import org.apache.kerby.kerberos.kerb.type.ticket.EncTicketPart;
import org.apache.kerby.kerberos.kerb.type.ticket.SgtTicket;
import org.apache.kerby.kerberos.kerb.type.ticket.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.model.NodeMetadata;
import org.taniwha.util.JwtTokenUtil;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;

@Service
public class NodeAccessService {

    private static final Logger logger = LoggerFactory.getLogger(NodeAccessService.class);
    private final JwtTokenUtil jwtTokenUtil;
    private final KerberosService kerberosService;
    private final FileService fileService;

    private static final int MAX_EXCHANGE_STATE = 10_000;
    private final KerberosAuthorizationStore authorizations =
            new KerberosAuthorizationStore(MAX_EXCHANGE_STATE, Clock.systemUTC());
    private final PrincipalService principalService;

    @Value("${kerberos.realm}")
    private String expectedRealm;
    @Value("${kerberos.ticket.version}")
    private int expectedTicketVer;
    @Value("${node.ip}")
    private String nodeIp;

    public NodeAccessService(JwtTokenUtil jwtTokenUtil, KerberosService kerberosService, FileService fileService, PrincipalService principalService) {
        this.jwtTokenUtil = jwtTokenUtil;
        this.kerberosService = kerberosService;
        this.fileService = fileService;
        this.principalService = principalService;
    }

    public NodeMetadata getMetadata() {
        return fileService.parseNodeMetadata();
    }

    public String getRawMetadata() {
        return fileService.getRawNodeMetadata();
    }

    public boolean verifyKerberosToken(String sgt) throws IOException {
        KerberosService.ServiceTicketEnvelope envelope = kerberosService.decodeSgtTicket(sgt);
        SgtTicket sgtTicket = envelope.ticket();
        AuthenticatedTicket authenticatedTicket = authenticateServiceTicket(sgtTicket);
        if (authenticatedTicket != null
                && kerberosService.verifyAuthenticator(
                envelope.authenticator(),
                authenticatedTicket.sessionKey(),
                authenticatedTicket.clientName(),
                authenticatedTicket.clientRealm())) {
            String fingerprint = kerberosService.ticketFingerprint(sgtTicket);
            if (!authorizations.authorize(fingerprint, authenticatedTicket.clientPrincipal(),
                    authenticatedTicket.expiresAt())) {
                logger.warn("Rejected replayed or over-capacity Kerberos authorization exchange");
                return false;
            }
            logger.debug("Kerberos token is valid");
            return true;
        } else {
            logger.error("Kerberos token validation failed");
            return false;
        }
    }

    public boolean verifySgtTicket(SgtTicket sgtTicket) {
        return authenticateServiceTicket(sgtTicket) != null;
    }

    private AuthenticatedTicket authenticateServiceTicket(SgtTicket sgtTicket) {
        try {
            if (!isTicketEnvelopeValid(sgtTicket)) {
                return null;
            }
            if (sgtTicket.getEncKdcRepPart() == null || sgtTicket.getEncKdcRepPart().getKey() != null) {
                logger.warn("Rejected Kerberos envelope carrying caller-supplied session-key material");
                return null;
            }
            byte[] keytabData = principalService.getKeytab();
            if (keytabData == null) return null;
            Ticket serviceTicket = sgtTicket.getTicket();
            EncryptionType ticketEncryptionType = serviceTicket.getEncryptedEncPart().getEType();
            EncryptionKey serviceKey = kerberosService.loadKeyFromKeytab(
                    getHostName(nodeIp) + '@' + expectedRealm, keytabData, ticketEncryptionType);
            EncTicketPart encTicketPart = kerberosService.decryptServiceTicket(serviceTicket, serviceKey);
            if (encTicketPart.getKey() == null || encTicketPart.getCname() == null
                    || encTicketPart.getFlags() == null || encTicketPart.getFlags().isInvalid()
                    || encTicketPart.getAuthTime() == null || encTicketPart.getEndTime() == null
                    || !expectedRealm.equals(encTicketPart.getCrealm())
                    || !samePrincipalIdentity(encTicketPart.getCname().getName(),
                    sgtTicket.getClientPrincipal().getName(), expectedRealm)) {
                logger.warn("Encrypted service-ticket client identity does not match the envelope");
                return null;
            }

            KerberosTime now = KerberosTime.now();
            if ((encTicketPart.getAuthTime() != null && encTicketPart.getAuthTime().greaterThan(now))
                    || (encTicketPart.getStartTime() != null && encTicketPart.getStartTime().greaterThan(now))
                    || (encTicketPart.getEndTime() != null && encTicketPart.getEndTime().lessThan(now))
                    || (encTicketPart.getRenewtill() != null && encTicketPart.getRenewtill().lessThan(now))) {
                logger.warn("Encrypted service ticket is outside its validity interval");
                return null;
            }
            logger.info("Token validation successful. Identity and session key derived from encrypted ticket.");
            return new AuthenticatedTicket(
                    encTicketPart.getCname().getName(),
                    encTicketPart.getCrealm(),
                    encTicketPart.getCname().getName() + "@" + encTicketPart.getCrealm(),
                    encTicketPart.getKey(),
                    encTicketPart.getEndTime().getTime());
        } catch (KrbException | IOException | RuntimeException e) {
            logger.warn("Error analyzing encrypted service ticket: {}", e.toString());
            return null;
        }
    }

    private boolean samePrincipalIdentity(String ticketName, String envelopePrincipal, String realm) {
        if (ticketName == null || envelopePrincipal == null) {
            return false;
        }
        String normalizedEnvelope = envelopePrincipal;
        int separator = envelopePrincipal.lastIndexOf('@');
        if (separator > 0) {
            if (!realm.equals(envelopePrincipal.substring(separator + 1))) {
                return false;
            }
            normalizedEnvelope = envelopePrincipal.substring(0, separator);
        }
        return ticketName.equals(normalizedEnvelope);
    }

    private boolean isTicketEnvelopeValid(SgtTicket sgtTicket) {

        Ticket ticket = sgtTicket.getTicket();
        if (ticket.getTktvno() != expectedTicketVer) {
            logger.error("Ticket version is invalid. Expected: {}, Found: {}", expectedTicketVer, ticket.getTktvno());
            return false;
        }

        if (!expectedRealm.equals(ticket.getRealm())) {
            logger.error("Ticket realm does not match the expected realm. Expected: {}, Found: {}", expectedRealm, ticket.getRealm());
            return false;
        }

        String expectedHostName = getHostName(nodeIp);
        if (!expectedHostName.equals(ticket.getSname().getName())) {
            logger.error("Ticket SName does not match the expected service name. Expected: {}, Found: {}", expectedHostName, ticket.getSname().getName());
            return false;
        }

        if (ticket.getEncryptedEncPart() == null
                || ticket.getEncryptedEncPart().getCipher() == null
                || ticket.getEncryptedEncPart().getCipher().length == 0) {
            logger.error("Encrypted service-ticket body is missing.");
            return false;
        }

        logger.debug("Ticket is valid with Tktvno: {}, Realm: {}, SName: {}, AuthTime: {}, EndTime: {}, RenewTill: {}",
                ticket.getTktvno(), ticket.getRealm(), ticket.getSname().getName(),
                "encrypted", "encrypted", "encrypted");

        return true;
    }


    public boolean validateUserToken(String jwtToken, String sgtToken) {
        try {
            if (!jwtTokenUtil.isCentralUserToken(jwtToken)) {
                return false;
            }
            String username = jwtTokenUtil.getUsernameFromToken(jwtToken);
            SgtTicket sgtTicket = kerberosService.decodeSgtTicket(sgtToken).ticket();
            AuthenticatedTicket authenticatedTicket = authenticateServiceTicket(sgtTicket);
            if (authenticatedTicket == null || !authenticatedTicket.clientPrincipal().equals(username + "@" + expectedRealm)) {
                logger.warn("JWT subject does not match the encrypted Kerberos client identity");
                return false;
            }
            String fingerprint = kerberosService.ticketFingerprint(sgtTicket);
            return authorizations.consume(fingerprint, authenticatedTicket.clientPrincipal());
        } catch (Exception e) {
            logger.error("Error validating user token", e);
            return false;
        }
    }

    private record AuthenticatedTicket(String clientName, String clientRealm, String clientPrincipal,
                                       EncryptionKey sessionKey, long expiresAt) {}

    public String getHostName(String ip) {
        String hostPrincipal = ip;
        String scheme = "";

        try {
            URI uri = new URI(ip);
            String host = uri.getHost();
            hostPrincipal = (host != null) ? host : ip;

            String uriScheme = uri.getScheme();
            if ("http".equalsIgnoreCase(uriScheme))
                scheme = "HTTP/";
            else if ("https".equalsIgnoreCase(uriScheme))
                scheme = "HTTPS/";
        } catch (URISyntaxException e) {
            logger.warn("Failed to parse URI from ip: {}. Using the original value.", ip, e);
        }
        return scheme + hostPrincipal;
    }
}
