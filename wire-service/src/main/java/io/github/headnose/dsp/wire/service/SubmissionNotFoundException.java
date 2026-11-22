package io.github.headnose.dsp.wire.service;

/** No submission with this ID. Mapped to 404. */
public class SubmissionNotFoundException extends RuntimeException {

    public SubmissionNotFoundException(String message) {
        super(message);
    }
}