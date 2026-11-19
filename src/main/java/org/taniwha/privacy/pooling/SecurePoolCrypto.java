package org.taniwha.privacy.pooling;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

// Full-participation pairwise masks: X25519, HKDF-SHA256, AES-256-CTR.
final class SecurePoolCrypto {
    private SecurePoolCrypto() {}

    static KeyPair exchangeKey() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("X25519").generateKeyPair();
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value)));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }

    static String publicKey(PublicKey key) { return Base64.getEncoder().encodeToString(key.getEncoded()); }

    static SecurePoolEnvelope sign(String payload, PrivateKey key) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(bytes(payload));
        return new SecurePoolEnvelope(payload, Base64.getEncoder().encodeToString(signature.sign()));
    }

    static boolean verify(SecurePoolEnvelope envelope, String publicKey) throws GeneralSecurityException {
        if (envelope == null || envelope.payload() == null || envelope.payload().length() > 2_000_000
                || envelope.signature()==null || envelope.signature().length()>4096) return false;
        var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(key);
        signature.update(bytes(envelope.payload()));
        return signature.verify(Base64.getDecoder().decode(envelope.signature()));
    }

    static void mask(long[] values, PrivateKey ownKey, String peerKey, String context, boolean add) throws GeneralSecurityException {
        var peer = KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(peerKey)));
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(ownKey);
        agreement.doPhase(peer, true);
        byte[] secret = agreement.generateSecret();
        byte[] salt = MessageDigest.getInstance("SHA-256").digest(bytes(context));
        byte[] prk = hmac(salt, secret);
        byte[] info = bytes("MEDIATA secure pooling mask v1\n" + context + "\u0001");
        byte[] key = hmac(prk, info);
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
        ByteBuffer stream = ByteBuffer.wrap(cipher.doFinal(new byte[Math.multiplyExact(values.length, 8)]));
        for (int index = 0; index < values.length; index++) {
            long mask = stream.getLong();
            // Arithmetic intentionally wraps modulo 2^64; only the sum is decoded.
            values[index] += add ? mask : -mask;
        }
        java.util.Arrays.fill(secret, (byte) 0);
        java.util.Arrays.fill(prk, (byte) 0);
        java.util.Arrays.fill(key, (byte) 0);
    }

    static String encode(long[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(Math.multiplyExact(values.length, 8));
        for (long value : values) buffer.putLong(value);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    static long[] decode(String encoded, int length) {
        if (encoded == null || encoded.length() > 800_000) throw new IllegalArgumentException("Invalid masked vector");
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length != length * 8) throw new IllegalArgumentException("Masked vector length differs");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long[] values = new long[length];
        for (int index = 0; index < length; index++) values[index] = buffer.getLong();
        return values;
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
