package org.taniwha.util;

import org.junit.jupiter.api.Test;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class KeyPairUtilTest {

    @Test
    void constructor_initializesNonNullKeys() {
        KeyPairUtil util = new KeyPairUtil();

        PrivateKey priv = util.getPrivateKey();
        PublicKey pub = util.getPublicKey();
        assertThat(priv).isNotNull();
        assertThat(pub).isNotNull();

        assertThat(priv.getAlgorithm()).isEqualTo("RSA");
        assertThat(priv.getFormat()).isEqualTo("PKCS#8");
        assertThat(pub.getAlgorithm()).isEqualTo("RSA");
        assertThat(pub.getFormat()).isEqualTo("X.509");
    }

    @Test
    void getPublicKeyBase64_decodesToTheSameEncodedKey() {
        KeyPairUtil util = new KeyPairUtil();
        PublicKey pub = util.getPublicKey();
        byte[] expected = pub.getEncoded();

        String b64 = util.getPublicKeyBase64();
        assertThat(b64).isNotBlank();

        byte[] actual = Base64.getDecoder().decode(b64);
        assertThat(actual).containsExactly(expected);
    }
}
