package io.github.headnose.dsp.wire.storage;

/**
 * The wire statement could not be stored. WireSendService maps this to
 * 503 "Wire statement storage unavailable" (D23): our own infrastructure is down, not an
 * upstream service, so 503 and not 502 (same reasoning as the Kafka failure in D13).
 */
public class WireStatementStorageException extends RuntimeException {

    public WireStatementStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}