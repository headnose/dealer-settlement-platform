package io.github.headnose.dsp.wire.service;

import io.github.headnose.dsp.wire.entity.WireRecipient;
import io.github.headnose.dsp.wire.messaging.DeliveryStatusEvent;
import io.github.headnose.dsp.wire.model.DeliveryStatus;
import io.github.headnose.dsp.wire.model.SubmissionStatus;
import io.github.headnose.dsp.wire.repository.WireRecipientRepository;
import io.github.headnose.dsp.wire.repository.WireSubmissionRepository;
import java.util.EnumSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workflow 4, last step: apply BlastMail's per-recipient result (D18, D19). */
@Service
public class DeliveryStatusService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryStatusService.class);

    private static final Set<DeliveryStatus> FINAL_STATUSES = EnumSet.of(DeliveryStatus.SENT, DeliveryStatus.FAILED);

    private final WireRecipientRepository recipientRepository;
    private final WireSubmissionRepository submissionRepository;

    public DeliveryStatusService(WireRecipientRepository recipientRepository,
                                 WireSubmissionRepository submissionRepository) {
        this.recipientRepository = recipientRepository;
        this.submissionRepository = submissionRepository;
    }

    // One transaction: the recipient update and the QUEUED -> SENT change commit together, or neither does.
    @Transactional
    public void apply(DeliveryStatusEvent event) {
        // 1. Only SENT / FAILED are results. PENDING (or null) from BlastMail is a bug on its side.
        if (event.deliveryStatus() == null || !FINAL_STATUSES.contains(event.deliveryStatus())) {
            throw new InvalidDeliveryStatusEventException(
                    "Unexpected deliveryStatus " + event.deliveryStatus() + " in event " + event.eventId());
        }

        // 2. Recipient must exist AND belong to the submission in the event
        WireRecipient recipient = recipientRepository
                .findByRecipientIdAndSubmission_SubmissionId(event.recipientId(), event.submissionId())
                .orElseThrow(() -> new InvalidDeliveryStatusEventException(
                        "No recipient " + event.recipientId() + " on submission " + event.submissionId()));

        // 3. Update the row (JPA dirty checking writes it at commit, no save() needed)
        recipient.recordDelivery(event.deliveryStatus(), event.processedTime());

        if (event.deliveryStatus() == DeliveryStatus.FAILED) {
            // D19: submission stays QUEUED; the recipient row shows FAILED; reason is logged, not stored
            log.warn("Delivery FAILED for recipient {} on submission {}: {}",
                    event.recipientId(), event.submissionId(), event.errorMessage());
            return;
        }

        // 4. Last recipient to succeed? Then the whole submission is SENT (D18)
        long notYetSent = recipientRepository
                .countBySubmission_SubmissionIdAndDeliveryStatusNot(event.submissionId(), DeliveryStatus.SENT);
        if (notYetSent == 0) {
            int updated = submissionRepository.transitionStatus(
                    event.submissionId(), SubmissionStatus.QUEUED, SubmissionStatus.SENT);
            if (updated == 0) {
                log.warn("All recipients SENT but submission {} was not QUEUED; status left unchanged",
                        event.submissionId());
            }
        }
    }
}