package io.github.headnose.dsp.wire.security;

import org.springframework.security.core.AuthenticationException;

/**
 * The ALB claims header is missing its proof: bad format, wrong algorithm, wrong signer, unknown key,
 * bad signature, expired. Extends AuthenticationException so Spring Security answers 401.
 * The message is for the log only, never for the client.
 */
public class InvalidAlbTokenException extends AuthenticationException {

    public InvalidAlbTokenException(String message) {
        super(message);
    }

    public InvalidAlbTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}