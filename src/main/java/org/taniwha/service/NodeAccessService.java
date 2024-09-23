package org.taniwha.service;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.type.KerberosTime;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionKey;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionType;
import org.apache.kerby.kerberos.kerb.type.kdc.EncKdcRepPart;
import org.apache.kerby.kerberos.kerb.type.ticket.EncTicketPart;
import org.apache.kerby.kerberos.kerb.type.ticket.SgtTicket;
import org.apache.kerby.kerberos.kerb.type.ticket.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.util.JwtTokenUtil;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class NodeAccessService {

    private static final Logger logger = LoggerFactory.getLogger(NodeAccessService.class);
    private final JwtTokenUtil jwtTokenUtil;
    private final KerberosService kerberosService;
    private final ConcurrentMap<String, SgtTicket> authorizedTokens = new ConcurrentHashMap<>();
    private final PrincipalService principalService;

    @Value("${kerberos.realm}")
    private String expectedRealm;
    @Value("${kerberos.ticket.version}")
    private int expectedTicketVer;
    @Value("${node.ip}")
    private String nodeIp;

    public NodeAccessService(JwtTokenUtil jwtTokenUtil, KerberosService kerberosService, PrincipalService principalService) {
        this.jwtTokenUtil = jwtTokenUtil;
        this.kerberosService = kerberosService;
        this.principalService = principalService;
    }

    public void storeToken(SgtTicket sgtTicket) {
        String subject = sgtTicket.getClientPrincipal().getName();
        authorizedTokens.put(subject, sgtTicket);
        logger.info("Stored SGT for subject: {}", subject);
    }

    public boolean verifyKerberosToken(String sgt) throws IOException {
        SgtTicket sgtTicket = (SgtTicket) kerberosService.decodeSgtTicket(sgt);

        if (verifySgtTicket(sgtTicket)) {
            storeToken(sgtTicket);
            logger.debug("Kerberos token is valid");
            return true;
        } else {
            logger.error("Kerberos token validation failed");
            return false;
        }
    }


    public boolean verifySgtTicket(SgtTicket sgtTicket) {
        if (isTicketValid(sgtTicket)) {
            try {
                byte[] keytabData = principalService.getKeytab();
                if (keytabData == null) return false;

                Ticket serviceTicket = sgtTicket.getTicket();
                EncKdcRepPart encKdcRepPart = sgtTicket.getEncKdcRepPart();

                byte[] sessionKeyBytes = encKdcRepPart.getKey().getKeyData();
                EncryptionType sessionKeyType = encKdcRepPart.getKey().getKeyType();

                EncryptionKey serviceKey = kerberosService.loadKeyFromKeytab(getHostName(nodeIp) + '@' + expectedRealm, keytabData, sessionKeyType);
                EncTicketPart encTicketPart = kerberosService.decryptServiceTicket(serviceTicket, serviceKey);
                EncryptionKey decryptedSessionKey = encTicketPart.getKey();
                byte[] decryptedSessionKeyBytes = decryptedSessionKey.getKeyData();

                // Compare the session key from the SGT with the decrypted session key
                if (Arrays.equals(sessionKeyBytes, decryptedSessionKeyBytes)) {
                    logger.info("Token validation successful. Derived key matches session key.");
                    return true;
                } else {
                    logger.error("Token validation failed. Derived key does not match session key.");
                    return false;
                }
            } catch (KrbException | IOException e) {
                logger.error("Error analyzing decoded SGT", e);
                return false;
            }
        }
        logger.error("Ticket is malformed");
        return false;
    }

    private boolean isTicketValid(SgtTicket sgtTicket) {
        Ticket ticket = sgtTicket.getTicket();
        EncKdcRepPart encKdcRepPart = sgtTicket.getEncKdcRepPart();
        KerberosTime now = KerberosTime.now();

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

        if (encKdcRepPart.getKey() == null) {
            logger.error("Encryption key in EncKdcRepPart is null.");
            return false;
        }

        if (encKdcRepPart.getAuthTime() != null && encKdcRepPart.getAuthTime().greaterThan(now)) {
            logger.error("AuthTime in EncKdcRepPart is in the future. AuthTime: {}, Current Time: {}", encKdcRepPart.getAuthTime(), now);
            return false;
        }

        if (encKdcRepPart.getEndTime() != null && encKdcRepPart.getEndTime().lessThan(now)) {
            logger.error("EndTime in EncKdcRepPart has already passed. EndTime: {}, Current Time: {}", encKdcRepPart.getEndTime(), now);
            return false;
        }

        if (encKdcRepPart.getRenewTill() != null && encKdcRepPart.getRenewTill().lessThan(now)) {
            logger.error("RenewTill in EncKdcRepPart has already passed. RenewTill: {}, Current Time: {}", encKdcRepPart.getRenewTill(), now);
            return false;
        }

        if (encKdcRepPart.getFlags() == null) {
            logger.error("Flags in EncKdcRepPart are null.");
            return false;
        }

        logger.debug("Ticket is valid with Tktvno: {}, Realm: {}, SName: {}, AuthTime: {}, EndTime: {}, RenewTill: {}",
                ticket.getTktvno(), ticket.getRealm(), ticket.getSname().getName(),
                encKdcRepPart.getAuthTime(), encKdcRepPart.getEndTime(), encKdcRepPart.getRenewTill());

        return true;
    }


    public boolean validateUserToken(String jwtToken, String sgtToken) {
        try {
            // Extract username from JWT token
            String username = jwtTokenUtil.getUsernameFromToken(jwtToken);
            String expectedPrincipal = username + "@" + expectedRealm;
            SgtTicket sgtTicket = (SgtTicket) kerberosService.decodeSgtTicket(sgtToken);
            SgtTicket storedSgtTicket = authorizedTokens.get(expectedPrincipal);
            if (storedSgtTicket == null) {
                logger.error("No stored SGT for principal: {}", expectedPrincipal);
                return false;
            }

            return verifySgtTicket(sgtTicket);
        } catch (Exception e) {
            logger.error("Error validating user token", e);
            return false;
        }
    }

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
