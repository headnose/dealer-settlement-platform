package io.github.headnose.dsp.wire.controller.dto;

import io.github.headnose.dsp.wire.entity.WireRecipient;
import io.github.headnose.dsp.wire.model.RecipientType;

/** One recipient in the response (blueprint: NotificationRecipientDto). */
public record NotificationRecipientDto(
        RecipientType recipientType,
        String name,
        String email
) {
    public static NotificationRecipientDto from(WireRecipient recipient) {
        return new NotificationRecipientDto(
                recipient.getRecipientType(),
                recipient.getName(),
                recipient.getEmail());
    }
}