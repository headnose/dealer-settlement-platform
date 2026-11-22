package io.github.headnose.dsp.wire.service;

/** Submission is not DRAFT, or another request claimed it first. Mapped to 409. */
public class SubmissionNotSendableException extends RuntimeException {

    public SubmissionNotSendableException(String message) {
        super(message);
    }
}