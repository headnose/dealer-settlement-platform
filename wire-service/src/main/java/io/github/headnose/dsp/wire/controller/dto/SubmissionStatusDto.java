package io.github.headnose.dsp.wire.controller.dto;

import io.github.headnose.dsp.wire.model.SubmissionStatus;

public record SubmissionStatusDto(String submissionId, SubmissionStatus status) {
}