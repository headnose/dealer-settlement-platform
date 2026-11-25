package io.github.headnose.dsp.settlement.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/** Mints x-amzn-oidc-data tokens the way the ALB does (ES256, kid + signer headers) for tests. */
public final class AlbTestTokens {

    public static final String HEADER = "x-amzn-oidc-data";
    public static final String KEY_ID = "test-key-1";
    public static final String SIGNER =
            "arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/dsp-test/0000000000000000";
    public static final KeyPair KEY_PAIR = newKeyPair();

    private AlbTestTokens() {
    }

    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1")); // P-256, as ES256 requires
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ECPublicKey publicKey() {
        return (ECPublicKey) KEY_PAIR.getPublic();
    }

    /** The test public key as PEM, for security.alb.public-key in web-layer tests. */
    public static String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    /** A valid token for "test-user" with these groups, expiring in 5 minutes (real clock). */
    public static String forGroups(String... groups) {
        return token("test-user", List.of(groups), Instant.now().plusSeconds(300));
    }

    public static String token(String subject, List<String> groups, Instant expiresAt) {
        return signed(KEY_PAIR, KEY_ID, SIGNER, subject, groups, expiresAt);
    }

    public static String signed(KeyPair keyPair, String keyId, String signer, String subject,
                                List<String> groups, Instant expiresAt) {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(keyId)
                .customParam(AlbClaimsVerifier.SIGNER_HEADER, signer)
                .build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("groups", groups)
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new ECDSASigner((ECPrivateKey) keyPair.getPrivate()));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }
}