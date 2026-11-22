package io.github.headnose.dsp.wire.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.headnose.dsp.wire.controller.dto.SubmissionStatusDto;
import io.github.headnose.dsp.wire.entity.WireRecipient;
import io.github.headnose.dsp.wire.entity.WireSubmission;
import io.github.headnose.dsp.wire.messaging.EmailDeliveryEvent;
import io.github.headnose.dsp.wire.messaging.EmailDeliveryPublishException;
import io.github.headnose.dsp.wire.messaging.EmailDeliveryPublisher;
import io.github.headnose.dsp.wire.model.SubmissionStatus;
import io.github.headnose.dsp.wire.model.WireType;
import io.github.headnose.dsp.wire.repository.WireSubmissionRepository;
import io.github.headnose.dsp.wire.storage.WireStatementStorage;
import io.github.headnose.dsp.wire.storage.WireStatementStorageException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WireSendServiceTest {

    private static final String SUBMISSION_ID = "SUB-1";
    private static final String LOAN_ID = "LN-1001";
    private static final String CONTENT = "<settlementWire/>";
    private static final String ATTACHMENT_PATH = "s3://wire-statements/SUB-1/LN-1001.xml";

    // 19:30:45.678 UTC = 14:30:45.678 in New York (EST in November)
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-20T19:30:45.678Z"), ZoneId.of("America/New_York"));

    @Mock
    private WireSubmissionRepository submissionRepository;

    @Mock
    private EmailDeliveryPublisher publisher;

    @Mock
    private WireStatementStorage statementStorage;

    @Captor
    private ArgumentCaptor<List<EmailDeliveryEvent>> eventsCaptor;

    private WireSendService service;

    @BeforeEach
    void setUp() {
        service = new WireSendService(submissionRepository, publisher, CLOCK, statementStorage);
    }

    @Test
    void sendsDraftByPublishingOneEventPerRecipientAndReturnsQueued() {
        WireRecipient dealer = recipient("REC-1", "Riverbend Motors", "accounts@riverbend-motors.example.com");
        WireRecipient audit = recipient("REC-2", "Compliance Audit Team", "compliance-audit@example.com");
        WireSubmission draft = submission(SubmissionStatus.DRAFT, dealer, audit);
        givenFound(draft);
        when(submissionRepository.existsByLoanIdAndWireTypeAndStatusIn(
                LOAN_ID, WireType.SETTLEMENT, Set.of(SubmissionStatus.QUEUED, SubmissionStatus.SENT)))
                .thenReturn(false);
        when(statementStorage.upload(SUBMISSION_ID, LOAN_ID, CONTENT)).thenReturn(ATTACHMENT_PATH);
        when(submissionRepository.transitionStatus(
                SUBMISSION_ID, SubmissionStatus.DRAFT, SubmissionStatus.QUEUED)).thenReturn(1);

        SubmissionStatusDto result = service.send(SUBMISSION_ID);

        assertThat(result).isEqualTo(new SubmissionStatusDto(SUBMISSION_ID, SubmissionStatus.QUEUED));

        // D23: upload -> claim -> publish, in that order
        InOrder order = inOrder(statementStorage, submissionRepository, publisher);
        order.verify(statementStorage).upload(SUBMISSION_ID, LOAN_ID, CONTENT);
        order.verify(submissionRepository)
                .transitionStatus(SUBMISSION_ID, SubmissionStatus.DRAFT, SubmissionStatus.QUEUED);
        order.verify(publisher).publishAll(eventsCaptor.capture());
        List<EmailDeliveryEvent> events = eventsCaptor.getValue();
        assertThat(events).extracting(EmailDeliveryEvent::recipientId).containsExactly("REC-1", "REC-2");
        assertThat(events).extracting(EmailDeliveryEvent::eventId).doesNotHaveDuplicates();

        EmailDeliveryEvent first = events.get(0);
        assertThat(first.eventId()).startsWith("EVT-");
        assertThat(first.eventType()).isEqualTo("EMAIL_DELIVERY_REQUESTED");
        assertThat(first.submissionId()).isEqualTo(SUBMISSION_ID);
        assertThat(first.loanId()).isEqualTo(LOAN_ID);
        assertThat(first.recipientName()).isEqualTo("Riverbend Motors");
        assertThat(first.email()).isEqualTo("accounts@riverbend-motors.example.com");
        assertThat(first.wireType()).isEqualTo(WireType.SETTLEMENT);
        assertThat(first.subject()).isEqualTo("Settlement Wire Statement - LN-1001");
        assertThat(events).extracting(EmailDeliveryEvent::attachmentPath).containsOnly(ATTACHMENT_PATH);
        assertThat(first.createdTime()).isEqualTo(LocalDateTime.of(2026, 11, 20, 14, 30, 45));

        verify(submissionRepository, never())
                .transitionStatus(SUBMISSION_ID, SubmissionStatus.QUEUED, SubmissionStatus.DRAFT);
    }

    @Test
    void unknownSubmissionIsNotFound() {
        when(submissionRepository.findWithRecipientsBySubmissionId(SUBMISSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.send(SUBMISSION_ID))
                .isInstanceOf(SubmissionNotFoundException.class);
        verifyNoInteractions(statementStorage, publisher);
    }

    @Test
    void submissionThatIsNotDraftCannotBeSent() {
        WireSubmission queued = submission(SubmissionStatus.QUEUED);
        givenFound(queued);

        assertThatThrownBy(() -> service.send(SUBMISSION_ID))
                .isInstanceOf(SubmissionNotSendableException.class)
                .hasMessageContaining("QUEUED");
        verify(submissionRepository, never()).transitionStatus(any(), any(), any());
        verifyNoInteractions(statementStorage, publisher);
    }

    @Test
    void draftIsRejectedWhenAnotherSubmissionForTheLoanWasAlreadySent() {
        WireSubmission draft = submission(SubmissionStatus.DRAFT);
        givenFound(draft);
        when(submissionRepository.existsByLoanIdAndWireTypeAndStatusIn(eq(LOAN_ID), eq(WireType.SETTLEMENT), any()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.send(SUBMISSION_ID))
                .isInstanceOf(DuplicateSubmissionException.class);
        verify(submissionRepository, never()).transitionStatus(any(), any(), any());
        verifyNoInteractions(statementStorage, publisher);
    }

    @Test
    void storageFailureLeavesTheDraftUntouchedAndPublishesNothing() {
        WireRecipient dealer = recipient("REC-1", "Riverbend Motors", "accounts@riverbend-motors.example.com");
        WireSubmission draft = submission(SubmissionStatus.DRAFT, dealer);
        givenFound(draft);
        givenNoDuplicate();
        WireStatementStorageException storageDown = new WireStatementStorageException(
                "Failed to upload wire statement", new RuntimeException("connection refused"));
        when(statementStorage.upload(SUBMISSION_ID, LOAN_ID, CONTENT)).thenThrow(storageDown);

        assertThatThrownBy(() -> service.send(SUBMISSION_ID)).isSameAs(storageDown);
        // D23: upload happens before the claim, so there is nothing to revert
        verify(submissionRepository, never()).transitionStatus(any(), any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void losingTheClaimRaceMeansNotSendableAndNothingIsPublished() {
        WireRecipient dealer = recipient("REC-1", "Riverbend Motors", "accounts@riverbend-motors.example.com");
        WireSubmission draft = submission(SubmissionStatus.DRAFT, dealer);
        givenFound(draft);
        givenNoDuplicate();
        when(submissionRepository.transitionStatus(
                SUBMISSION_ID, SubmissionStatus.DRAFT, SubmissionStatus.QUEUED)).thenReturn(0);

        assertThatThrownBy(() -> service.send(SUBMISSION_ID))
                .isInstanceOf(SubmissionNotSendableException.class);
        verifyNoInteractions(publisher);
    }

    @Test
    void failedPublishRevertsToDraftAndRethrows() {
        WireRecipient dealer = recipient("REC-1", "Riverbend Motors", "accounts@riverbend-motors.example.com");
        WireSubmission draft = submission(SubmissionStatus.DRAFT, dealer);
        givenFound(draft);
        givenNoDuplicate();
        when(submissionRepository.transitionStatus(
                SUBMISSION_ID, SubmissionStatus.DRAFT, SubmissionStatus.QUEUED)).thenReturn(1);
        EmailDeliveryPublishException kafkaDown =
                new EmailDeliveryPublishException("Kafka did not confirm", new RuntimeException("broker down"));
        doThrow(kafkaDown).when(publisher).publishAll(anyList());

        assertThatThrownBy(() -> service.send(SUBMISSION_ID)).isSameAs(kafkaDown);
        verify(submissionRepository)
                .transitionStatus(SUBMISSION_ID, SubmissionStatus.QUEUED, SubmissionStatus.DRAFT);
    }

    @Test
    void failedRevertKeepsThePublishErrorAndAttachesTheRevertError() {
        WireRecipient dealer = recipient("REC-1", "Riverbend Motors", "accounts@riverbend-motors.example.com");
        WireSubmission draft = submission(SubmissionStatus.DRAFT, dealer);
        givenFound(draft);
        givenNoDuplicate();
        when(submissionRepository.transitionStatus(
                SUBMISSION_ID, SubmissionStatus.DRAFT, SubmissionStatus.QUEUED)).thenReturn(1);
        EmailDeliveryPublishException kafkaDown =
                new EmailDeliveryPublishException("Kafka did not confirm", new RuntimeException("broker down"));
        doThrow(kafkaDown).when(publisher).publishAll(anyList());
        RuntimeException mysqlDown = new RuntimeException("mysql down");
        when(submissionRepository.transitionStatus(
                SUBMISSION_ID, SubmissionStatus.QUEUED, SubmissionStatus.DRAFT)).thenThrow(mysqlDown);

        assertThatThrownBy(() -> service.send(SUBMISSION_ID)).isSameAs(kafkaDown);
        assertThat(kafkaDown.getSuppressed()).containsExactly(mysqlDown);
    }

    // --- helpers ---

    private void givenFound(WireSubmission submission) {
        when(submissionRepository.findWithRecipientsBySubmissionId(SUBMISSION_ID))
                .thenReturn(Optional.of(submission));
    }

    private void givenNoDuplicate() {
        when(submissionRepository.existsByLoanIdAndWireTypeAndStatusIn(any(), any(), any())).thenReturn(false);
    }

    // lenient(): each test uses a different subset of these getters (a not-DRAFT submission
    // never reaches getRecipients()). Strict stubs would fail the test for "unused stubbing".
    private WireSubmission submission(SubmissionStatus status, WireRecipient... recipients) {
        WireSubmission submission = mock(WireSubmission.class);
        lenient().when(submission.getSubmissionId()).thenReturn(SUBMISSION_ID);
        lenient().when(submission.getLoanId()).thenReturn(LOAN_ID);
        lenient().when(submission.getWireType()).thenReturn(WireType.SETTLEMENT);
        lenient().when(submission.getStatus()).thenReturn(status);
        lenient().when(submission.getContent()).thenReturn(CONTENT);
        lenient().when(submission.getRecipients()).thenReturn(List.of(recipients));
        return submission;
    }

    private WireRecipient recipient(String recipientId, String name, String email) {
        WireRecipient recipient = mock(WireRecipient.class);
        lenient().when(recipient.getRecipientId()).thenReturn(recipientId);
        lenient().when(recipient.getName()).thenReturn(name);
        lenient().when(recipient.getEmail()).thenReturn(email);
        return recipient;
    }
}