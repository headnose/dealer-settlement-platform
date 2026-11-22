package io.github.headnose.dsp.wire.service;

/** Another submission for the same loan + wire type is already QUEUED or SENT (D12). Mapped to 409. */
public class DuplicateSubmissionException extends RuntimeException {

    public DuplicateSubmissionException(String message) {
        super(message);
    }
}