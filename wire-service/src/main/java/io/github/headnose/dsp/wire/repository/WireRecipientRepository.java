package io.github.headnose.dsp.wire.repository;

import io.github.headnose.dsp.wire.entity.WireRecipient;
import io.github.headnose.dsp.wire.model.DeliveryStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WireRecipientRepository extends JpaRepository<WireRecipient, String> {

    // Finds the recipient only if it belongs to the submission named in the event.
    // "Submission_SubmissionId" walks the @ManyToOne: recipient.submission.submissionId
    Optional<WireRecipient> findByRecipientIdAndSubmission_SubmissionId(String recipientId, String submissionId);

    // How many recipients of this submission are NOT yet in the given status?
    long countBySubmission_SubmissionIdAndDeliveryStatusNot(String submissionId, DeliveryStatus status);
}