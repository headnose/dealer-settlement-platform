package io.github.headnose.dsp.settlement.security;

import java.security.interfaces.ECPublicKey;

/**
 * Looks up the ALB's public key for a key id (the JWT "kid" header).
 * Locally: one configured key. In AWS: fetched from the regional ALB public-key endpoint and cached.
 */
@FunctionalInterface
public interface AlbPublicKeys {

    /** @return the key, or null if the key id is unknown */
    ECPublicKey forKeyId(String keyId);
}