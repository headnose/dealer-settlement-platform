package io.github.headnose.dsp.wire.service;

import io.github.headnose.dsp.wire.controller.dto.SubmissionStatusDto;
import io.github.headnose.dsp.wire.entity.WireRecipient;
import io.github.headnose.dsp.wire.entity.WireSubmission;
import io.github.headnose.dsp.wire.messaging.EmailDeliveryEvent;
import io.github.headnose.dsp.wire.messaging.EmailDeliveryPublishException;
import io.github.headnose.dsp.wire.messaging.EmailDeliveryPublisher;
import io.github.headnose.dsp.wire.model.SubmissionStatus;
import io.github.headnose.dsp.wire.repository.WireSubmissionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Workflow 4, PWT side: send a DRAFT wire by publishing one email-delivery
 * event per recipient. Status DRAFT -> QUEUED (D11-D13).
 */
@Service
public class WireSendService {

    private static final Logger log = LoggerFactory.getLogger(WireSendService.class);

    private static final Set<SubmissionStatus> ALREADY_SENDING =
            EnumSet.of(SubmissionStatus.QUEUED, SubmissionStatus.SENT);

    private final WireSubmissionRepository submissionRepository;
    private final EmailDeliveryPublisher publisher;
    private final Clock clock;

    public WireSendService(WireSubmissionRepository submissionRepository,
                           EmailDeliveryPublisher publisher,
                           Clock clock) {
        this.submissionRepository = submissionRepository;
        this.publisher = publisher;
        this.clock = clock;
    }

    // No @Transactional on purpose (D13): the claim must COMMIT before we publish.
    public SubmissionStatusDto send(String submissionId) {
        // 1. Load submission + recipients in one query (recipients are LAZY)
        WireSubmission submission = submissionRepository.findWithRecipientsBySubmissionId(submissionId)
                .orElseThrow(() -> new SubmissionNotFoundException(
                        "Submission " + submissionId + " not found"));

        // 2. Only a DRAFT can be sent
        if (submission.getStatus() != SubmissionStatus.DRAFT) {
            throw new SubmissionNotSendableException(
                    "Submission " + submissionId + " is " + submission.getStatus()
                            + "; only a DRAFT can be sent");
        }

        // 3. Duplicate guard (D12): another draft for this loan already went out
        if (submissionRepository.existsByLoanIdAndWireTypeAndStatusIn(
                submission.getLoanId(), submission.getWireType(), ALREADY_SENDING)) {
            throw new DuplicateSubmissionException(
                    "A " + submission.getWireType() + " wire for loan " + submission.getLoanId()
                            + " has already been sent");
        }

        // 4. Build all events BEFORE claiming, so a bug here can't leave the row stuck in QUEUED
        LocalDateTime createdTime = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        List<EmailDeliveryEvent> events = submission.getRecipients().stream()
                .map(recipient -> toEvent(submission, recipient, createdTime))
                .toList();

        // 5. Claim: atomic DRAFT -> QUEUED. 0 rows = another request won the race.
        int claimed = submissionRepository.transitionStatus(
                submissionId, SubmissionStatus.DRAFT, SubmissionStatus.QUEUED);
        if (claimed == 0) {
            throw new SubmissionNotSendableException(
                    "Submission " + submissionId + " is already being sent");
        }

        // 6. Publish; on failure put it back to DRAFT so the user can retry.
        //    A retry is safe: BlastMail de-duplicates on recipientId.
        try {
            publisher.publishAll(events);
        } catch (EmailDeliveryPublishException e) {
            revertToDraft(submissionId, e);
            throw e;
        }

        // 7. Done: emails are now BlastMail's job (asynchronous)
        return new SubmissionStatusDto(submissionId, SubmissionStatus.QUEUED);
    }

    private void revertToDraft(String submissionId, EmailDeliveryPublishException publishFailure) {
        try {
            submissionRepository.transitionStatus(
                    submissionId, SubmissionStatus.QUEUED, SubmissionStatus.DRAFT);
        } catch (RuntimeException revertFailure) {
            // Rare: Kafka AND MySQL failed. The row stays QUEUED with no events (needs the
            // outbox pattern to fix properly; see README limitations). Keep the original error.
            log.error("Could not revert submission {} to DRAFT after a failed publish",
                    submissionId, revertFailure);
            publishFailure.addSuppressed(revertFailure);
        }
    }

    private EmailDeliveryEvent toEvent(WireSubmission submission, WireRecipient recipient,
                                       LocalDateTime createdTime) {
        return new EmailDeliveryEvent(
                "EVT-" + UUID.randomUUID(),
                EmailDeliveryEvent.EMAIL_DELIVERY_REQUESTED,
                submission.getSubmissionId(),
                submission.getLoanId(),
                recipient.getRecipientId(),
                recipient.getName(),
                recipient.getEmail(),
                submission.getWireType(),
                "Settlement Wire Statement - " + submission.getLoanId(),
                null,   // attachmentPath: MinIO step (D15)
                createdTime);
    }
}