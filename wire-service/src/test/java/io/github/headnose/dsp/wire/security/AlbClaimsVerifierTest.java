package io.github.headnose.dsp.wire.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlbClaimsVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-25T17:00:00Z");

    private final AlbClaimsVerifier verifier = new AlbClaimsVerifier(
            keyId -> AlbTestTokens.KEY_ID.equals(keyId) ? AlbTestTokens.publicKey() : null,
            AlbTestTokens.SIGNER, "groups", Duration.ofSeconds(60), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void validTokenGivesSubjectAndGroups() {
        String token = AlbTestTokens.token("finops-user-1", List.of("FinanceOps"), NOW.plusSeconds(300));

        AlbIdentity identity = verifier.verify(token);

        assertThat(identity.subject()).isEqualTo("finops-user-1");
        assertThat(identity.groups()).containsExactly("FinanceOps");
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        KeyPair attacker = AlbTestTokens.newKeyPair();
        String forged = AlbTestTokens.signed(attacker, AlbTestTokens.KEY_ID, AlbTestTokens.SIGNER,
                "attacker", List.of("SettlementAdmin"), NOW.plusSeconds(300));

        assertThatThrownBy(() -> verifier.verify(forged))
                .isInstanceOf(InvalidAlbTokenException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void rejectsTokenFromAnotherLoadBalancer() {
        String token = AlbTestTokens.signed(AlbTestTokens.KEY_PAIR, AlbTestTokens.KEY_ID,
                "arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/other/1111111111111111",
                "finops-user-1", List.of("FinanceOps"), NOW.plusSeconds(300));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidAlbTokenException.class)
                .hasMessageContaining("load balancer");
    }

    @Test
    void rejectsExpiredTokenBeyondClockSkew() {
        String token = AlbTestTokens.token("finops-user-1", List.of("FinanceOps"), NOW.minusSeconds(61));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidAlbTokenException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void rejectsUnknownKeyId() {
        String token = AlbTestTokens.signed(AlbTestTokens.KEY_PAIR, "rotated-key-2", AlbTestTokens.SIGNER,
                "finops-user-1", List.of("FinanceOps"), NOW.plusSeconds(300));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidAlbTokenException.class)
                .hasMessageContaining("Unknown key id");
    }

    @Test
    void rejectsHs256EvenWithValidHeaders() throws Exception {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256)
                .keyID(AlbTestTokens.KEY_ID)
                .customParam(AlbClaimsVerifier.SIGNER_HEADER, AlbTestTokens.SIGNER)
                .build();
        SignedJWT jwt = new SignedJWT(header, new JWTClaimsSet.Builder()
                .subject("attacker")
                .expirationTime(Date.from(NOW.plusSeconds(300)))
                .build());
        jwt.sign(new MACSigner(new byte[32]));

        assertThatThrownBy(() -> verifier.verify(jwt.serialize()))
                .isInstanceOf(InvalidAlbTokenException.class)
                .hasMessageContaining("Unexpected algorithm");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> verifier.verify("not-a-jwt"))
                .isInstanceOf(InvalidAlbTokenException.class);
    }
}