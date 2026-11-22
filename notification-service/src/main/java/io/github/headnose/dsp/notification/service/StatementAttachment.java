package io.github.headnose.dsp.notification.service;

/** A downloaded wire statement, ready to attach to an email. */
public record StatementAttachment(String fileName, byte[] content, String contentType) {
}