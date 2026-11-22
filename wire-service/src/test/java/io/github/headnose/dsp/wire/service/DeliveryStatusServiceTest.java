package io.github.headnose.dsp.wire.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.headnose.dsp.wire.entity.WireRecipient;
import io.github.headnose.dsp.wire.messaging.DeliveryStatusEvent;
import io.github.headnose.dsp.wire.model.DeliveryStatus;
import io.github.headnose.dsp.wire.model.SubmissionStatus;
import io.github.headnose.dsp.wire.repository.WireRecipientRepository;
import io.github.headnose.dsp.wire.repository.WireSubmissionRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeliveryStatusServiceTest {

    private static final String SUBMISSION_ID = "SUB-1";
    private static final String RECIPIENT_ID = "REC-1";
    private static final LocalDateTime PROCESSED = LocalDateTime.of(2026, 11, 21, 14, 35, 41);

    @Mock
    private WireRecipientRepository recipientRepository;

    @Mock
    private WireSubmissionRepository submissionRepository;

    private DeliveryStatusService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryStatusService(recipientRepository, submissionRepository);
    }

    @Test
    void lastRecipientSentMarksSubmissionSent() {
        WireRecipient recipient = givenRecipientFound();
        when(recipientRepository.countBySubmission_SubmissionIdAndDeliveryStatusNot(SUBMISSION_ID, DeliveryStatus.SENT))
                .thenReturn(0L);
        when(submissionRepository.transitionStatus(SUBMISSION_ID, SubmissionStatus.QUEUED, SubmissionStatus.SENT))
                .thenReturn(1);

        service.apply(event(DeliveryStatus.SENT));

        verify(recipient).recordDelivery(DeliveryStatus.SENT, PROCESSED);
        verify(submissionRepository).transitionStatus(SUBMISSION_ID, SubmissionStatus.QUEUED, SubmissionStatus.SENT);
    }

    @Test
    void sentWhileOthersArePendingLeavesSubmissionQueued() {
        WireRecipient recipient = givenRecipientFound();
        when(recipientRepository.countBySubmission_SubmissionIdAndDeliveryStatusNot(SUBMISSION_ID, DeliveryStatus.SENT))
                .thenReturn(3L);

        service.apply(event(DeliveryStatus.SENT));

        verify(recipient).recordDelivery(DeliveryStatus.SENT, PROCESSED);
        verifyNoInteractions(submissionRepository);
    }

    @Test
    void failedUpdatesRecipientButNeverTheSubmission() {
        WireRecipient recipient = givenRecipientFound();

        service.apply(new DeliveryStatusEvent("EVT-2", "EMAIL_DELIVERY_FAILED", SUBMISSION_ID, "LN-1001",
                RECIPIENT_ID, DeliveryStatus.FAILED, "Mailbox unavailable", PROCESSED));

        verify(recipient).recordDelivery(DeliveryStatus.FAILED, PROCESSED);
        verify(recipientRepository, never()).countBySubmission_SubmissionIdAndDeliveryStatusNot(any(), any());
        verifyNoInteractions(submissionRepository);
    }

    @Test
    void unknownRecipientOrWrongSubmissionIsInvalid() {
        when(recipientRepository.findByRecipientIdAndSubmission_SubmissionId(RECIPIENT_ID, SUBMISSION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(event(DeliveryStatus.SENT)))
                .isInstanceOf(InvalidDeliveryStatusEventException.class);
        verifyNoInteractions(submissionRepository);
    }

    @Test
    void pendingIsNotAValidResult() {
        assertThatThrownBy(() -> service.apply(event(DeliveryStatus.PENDING)))
                .isInstanceOf(InvalidDeliveryStatusEventException.class);
        verifyNoInteractions(recipientRepository, submissionRepository);
    }

    private WireRecipient givenRecipientFound() {
        WireRecipient recipient = mock(WireRecipient.class);
        when(recipientRepository.findByRecipientIdAndSubmission_SubmissionId(RECIPIENT_ID, SUBMISSION_ID))
                .thenReturn(Optional.of(recipient));
        return recipient;
    }

    private static DeliveryStatusEvent event(DeliveryStatus status) {
        return new DeliveryStatusEvent("EVT-1", "EMAIL_DELIVERY_COMPLETED", SUBMISSION_ID, "LN-1001",
                RECIPIENT_ID, status, null, PROCESSED);
    }
}