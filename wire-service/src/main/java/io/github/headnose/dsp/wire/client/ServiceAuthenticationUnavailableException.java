package io.github.headnose.dsp.wire.client;

/**
 * wire-service could not get its own access token for settlement-service: the IdP is down or rejected
 * wire-service's credentials (S7). settlement-service itself was never called.
 */
public class ServiceAuthenticationUnavailableException extends RuntimeException {

    public ServiceAuthenticationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}