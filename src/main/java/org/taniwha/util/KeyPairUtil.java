package org.taniwha.util;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.*;
import java.util.Base64;

@Component
@Getter
public class KeyPairUtil {
    private static final Logger logger = LoggerFactory.getLogger(KeyPairUtil.class);
    private PrivateKey privateKey;
    private PublicKey publicKey;

    public KeyPairUtil() {
        KeyPairGenerator keyGen;
        KeyPair pair;
        try {
            keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            pair = keyGen.generateKeyPair();
            this.privateKey = pair.getPrivate();
            this.publicKey = pair.getPublic();
        } catch (NoSuchAlgorithmException e) {
            logger.error("Error in KeyPairUtil. This should never be reached.");
        }
    }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }
}
