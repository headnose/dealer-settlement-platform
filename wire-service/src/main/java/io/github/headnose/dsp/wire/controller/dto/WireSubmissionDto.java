package io.github.headnose.dsp.wire.controller.dto;

import io.github.headnose.dsp.wire.entity.WireSubmission;
import io.github.headnose.dsp.wire.model.SubmissionStatus;

import java.time.LocalDateTime;
import java.util.List;

/** POST /api/v1/wires response (blueprint: WireSubmissionDto). */
public record WireSubmissionDto(
        String submissionId,
        SubmissionStatus status,
        LocalDateTime createdTime,
        List<NotificationRecipientDto> notificationRecipients
) {
    public static WireSubmissionDto from(WireSubmission submission) {
        return new WireSubmissionDto(
                submission.getSubmissionId(),
                submission.getStatus(),
                submission.getCreatedTime(),
                submission.getRecipients().stream()
                        .map(NotificationRecipientDto::from)
                        .toList());
    }
}