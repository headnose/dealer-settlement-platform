package io.github.headnose.dsp.settlement.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.interfaces.ECPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Blueprint "Identity Propagation": verifies the x-amzn-oidc-data JWT that the ALB signs (ES256)
 * after it has authenticated the user. Nothing in the token is trusted until every check passes.
 */
public class AlbClaimsVerifier {

    /** Custom JWT header parameter in which the ALB puts its own ARN. */
    static final String SIGNER_HEADER = "signer";

    private final AlbPublicKeys publicKeys;
    private final String expectedSigner;
    private final String groupsClaim;
    private final Duration clockSkew;
    private final Clock clock;

    public AlbClaimsVerifier(AlbPublicKeys publicKeys, String expectedSigner, String groupsClaim,
                             Duration clockSkew, Clock clock) {
        this.publicKeys = publicKeys;
        this.expectedSigner = expectedSigner;
        this.groupsClaim = groupsClaim;
        this.clockSkew = clockSkew;
        this.clock = clock;
    }

    public AlbIdentity verify(String token) {
        SignedJWT jwt = parse(token);
        JWSHeader header = jwt.getHeader();

        // Only ES256: rejects "alg": "HS256" / "none" tricks (algorithm confusion).
        if (!JWSAlgorithm.ES256.equals(header.getAlgorithm())) {
            throw new InvalidAlbTokenException("Unexpected algorithm " + header.getAlgorithm());
        }
        // ALB keys are per region, shared by all ALBs there: the signature alone proves "some ALB".
        if (!expectedSigner.equals(header.getCustomParam(SIGNER_HEADER))) {
            throw new InvalidAlbTokenException("Token not signed by the expected load balancer");
        }
        String keyId = header.getKeyID();
        ECPublicKey key = keyId == null ? null : publicKeys.forKeyId(keyId);
        if (key == null) {
            throw new InvalidAlbTokenException("Unknown key id " + keyId);
        }
        if (!signatureValid(jwt, key)) {
            throw new InvalidAlbTokenException("Invalid signature");
        }

        JWTClaimsSet claims = claims(jwt);
        checkNotExpired(claims.getExpirationTime());
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidAlbTokenException("Token has no subject");
        }
        return new AlbIdentity(subject, groups(claims));
    }

    private static SignedJWT parse(String token) {
        try {
            return SignedJWT.parse(token);
        } catch (ParseException e) {
            throw new InvalidAlbTokenException("Not a signed JWT", e);
        }
    }

    private static boolean signatureValid(SignedJWT jwt, ECPublicKey key) {
        try {
            return jwt.verify(new ECDSAVerifier(key));
        } catch (JOSEException e) {
            throw new InvalidAlbTokenException("Signature check failed", e);
        }
    }

    private static JWTClaimsSet claims(SignedJWT jwt) {
        try {
            return jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new InvalidAlbTokenException("Unreadable claims", e);
        }
    }

    private void checkNotExpired(Date expiresAt) {
        if (expiresAt == null) {
            throw new InvalidAlbTokenException("Token has no expiry");
        }
        Instant expiry = expiresAt.toInstant();
        if (clock.instant().isAfter(expiry.plus(clockSkew))) {
            throw new InvalidAlbTokenException("Token expired at " + expiry);
        }
    }

    /** No groups claim = authenticated user without roles (403 later, not 401). */
    private Set<String> groups(JWTClaimsSet claims) {
        try {
            List<String> groups = claims.getStringListClaim(groupsClaim);
            return groups == null ? Set.of() : Set.copyOf(groups);
        } catch (ParseException e) {
            throw new InvalidAlbTokenException("Claim " + groupsClaim + " is not a list of strings", e);
        }
    }
}