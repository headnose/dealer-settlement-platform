package io.github.headnose.dsp.settlement.security;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Reads a PEM "BEGIN PUBLIC KEY" (X.509 SubjectPublicKeyInfo) EC key with the JDK only. */
final class PemKeys {

    private PemKeys() {
    }

    static ECPublicKey ecPublicKey(String pem) {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException e) {
            throw new IllegalStateException("security.alb.public-key is not a PEM EC public key", e);
        }
    }
}