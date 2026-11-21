package io.github.headnose.dsp.wire.repository;

import io.github.headnose.dsp.wire.entity.WireSubmission;
import io.github.headnose.dsp.wire.model.SubmissionStatus;
import io.github.headnose.dsp.wire.model.WireType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WireSubmissionRepository extends JpaRepository<WireSubmission, String> {

    // Loads the submission AND its recipients in one query. The recipients list is LAZY,
    // and send() runs outside a transaction (D13), so they must be fetched up front.
    @EntityGraph(attributePaths = "recipients")
    Optional<WireSubmission> findWithRecipientsBySubmissionId(String submissionId);

    // Duplicate-draft guard (D12): has another submission for this loan + wire type
    // already been queued or sent?
    boolean existsByLoanIdAndWireTypeAndStatusIn(String loanId, WireType wireType,
                                                 Collection<SubmissionStatus> statuses);

    // Atomic conditional status change (D13). Returns 1 if this call won, 0 if the row was
    // no longer in `from` (already sent, or another request got there first).
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update WireSubmission s set s.status = :to "
            + "where s.submissionId = :submissionId and s.status = :from")
    int transitionStatus(@Param("submissionId") String submissionId,
                         @Param("from") SubmissionStatus from,
                         @Param("to") SubmissionStatus to);
}