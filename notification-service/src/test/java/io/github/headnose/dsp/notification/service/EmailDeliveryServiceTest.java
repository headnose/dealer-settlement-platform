package io.github.headnose.dsp.notification.service;

import io.github.headnose.dsp.notification.messaging.DeliveryStatusEvent;
import io.github.headnose.dsp.notification.messaging.DeliveryStatusPublisher;
import io.github.headnose.dsp.notification.messaging.EmailDeliveryEvent;
import io.github.headnose.dsp.notification.model.DeliveryStatus;
import io.github.headnose.dsp.notification.service.DeliveryIdempotencyStore.ClaimResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailDeliveryServiceTest {

    // 2026-11-22T01:30Z = 2026-11-21 20:30 in New York
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-22T01:30:00Z"), ZoneId.of("America/New_York"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 21, 20, 30, 0);

    private static final String PATH = "s3://wire-statements/SUB-1/LN-1001.xml";
    private static final String KEY = "SUB-1/LN-1001.xml";
    private static final StatementAttachment ATTACHMENT = new StatementAttachment(
            "LN-1001.xml", "<settlementWire/>".getBytes(StandardCharsets.UTF_8), "application/xml");

    @Mock private DeliveryIdempotencyStore idempotencyStore;
    @Mock private NotificationMailer mailer;
    @Mock private DeliveryStatusPublisher publisher;
    @Mock private StatementDownloader downloader;

    private EmailDeliveryService service;

    @BeforeEach
    void setUp() {
        service = new EmailDeliveryService(idempotencyStore, mailer, publisher, CLOCK, downloader);
    }

    @Test
    void newRecipient_downloadsStatement_sendsEmail_marksSent_publishesSent() {
        EmailDeliveryEvent event = event("REC-1");
        givenValidPath();
        when(idempotencyStore.claim("REC-1")).thenReturn(ClaimResult.CLAIMED);
        when(downloader.download(KEY)).thenReturn(ATTACHMENT);

        service.deliver(event);

        InOrder order = inOrder(downloader, mailer, idempotencyStore, publisher);
        order.verify(downloader).download(KEY);
        order.verify(mailer).send(event, ATTACHMENT);
        order.verify(idempotencyStore).markSent("REC-1");
        DeliveryStatusEvent published = captureOrdered(order);

        assertThat(published.eventId()).startsWith("EVT-");
        assertThat(published.eventType()).isEqualTo(DeliveryStatusEvent.EMAIL_DELIVERY_COMPLETED);
        assertThat(published.submissionId()).isEqualTo("SUB-1");
        assertThat(published.loanId()).isEqualTo("LN-1001");
        assertThat(published.recipientId()).isEqualTo("REC-1");
        assertThat(published.deliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(published.errorMessage()).isNull();
        assertThat(published.processedTime()).isEqualTo(NOW);
    }

    @Test
    void alreadySent_doesNotDownloadOrEmailAgain_butRepublishesSent() {
        givenValidPath();
        when(idempotencyStore.claim("REC-1")).thenReturn(ClaimResult.ALREADY_SENT);

        service.deliver(event("REC-1"));

        verify(downloader, never()).download(any());
        verifyNoInteractions(mailer);
        verify(idempotencyStore, never()).markSent(any());
        assertThat(capturePublished().deliveryStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    void inProgress_throwsRetryable_andDoesNothingElse() {
        givenValidPath();
        when(idempotencyStore.claim("REC-1")).thenReturn(ClaimResult.IN_PROGRESS);

        assertThatThrownBy(() -> service.deliver(event("REC-1")))
                .isInstanceOf(DeliveryInProgressException.class);

        verify(downloader, never()).download(any());
        verifyNoInteractions(mailer, publisher);
    }

    @Test
    void smtpFailure_releasesKey_andThrowsRetryable() {
        EmailDeliveryEvent event = event("REC-1");
        givenValidPath();
        when(idempotencyStore.claim("REC-1")).thenReturn(ClaimResult.CLAIMED);
        when(downloader.download(KEY)).thenReturn(ATTACHMENT);
        doThrow(new MailSendException("Connection refused")).when(mailer).send(event, ATTACHMENT);

        assertThatThrownBy(() -> service.deliver(event))
                .isInstanceOf(EmailSendException.class)
                .hasCauseInstanceOf(MailSendException.class);

        verify(idempotencyStore).release("REC-1");
        verify(idempotencyStore, never()).markSent(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void missingStatement_releasesKey_sendsNoEmail_andRethrows() {
        givenValidPath();
        when(idempotencyStore.claim("REC-1")).thenReturn(ClaimResult.CLAIMED);
        AttachmentNotFoundException notFound = new AttachmentNotFoundException(PATH, null);
        when(downloader.download(KEY)).thenThrow(notFound);

        assertThatThrownBy(() -> service.deliver(event("REC-1"))).isSameAs(notFound);

        verify(idempotencyStore).release("REC-1");
        verify(idempotencyStore, never()).markSent(any());
        verifyNoInteractions(mailer, publisher);
    }

    @Test
    void storageDown_releasesKey_sendsNoEmail_andRethrows() {
        givenValidPath();
        when(idempotencyStore.claim("REC-1")).thenReturn(ClaimResult.CLAIMED);
        AttachmentUnavailableException storageDown =
                new AttachmentUnavailableException(PATH, new RuntimeException("connection refused"));
        when(downloader.download(KEY)).thenThrow(storageDown);

        assertThatThrownBy(() -> service.deliver(event("REC-1"))).isSameAs(storageDown);

        verify(idempotencyStore).release("REC-1");
        verifyNoInteractions(mailer, publisher);
    }

    @Test
    void invalidEvent_isRejectedBeforeTouchingRedis() {
        assertThatThrownBy(() -> service.deliver(event(" ")))
                .isInstanceOf(InvalidEmailDeliveryEventException.class)
                .hasMessage("Missing recipientId");

        verifyNoInteractions(idempotencyStore, mailer, publisher, downloader);
    }

    @Test
    void badAttachmentPath_isRejectedBeforeTouchingRedis() {
        when(downloader.keyOf(PATH)).thenThrow(new InvalidEmailDeliveryEventException("bad path"));

        assertThatThrownBy(() -> service.deliver(event("REC-1")))
                .isInstanceOf(InvalidEmailDeliveryEventException.class);

        verifyNoInteractions(idempotencyStore, mailer, publisher);
    }

    @Test
    void reportFailure_publishesFailedWithSmtpMessage() {
        EmailSendException failure = new EmailSendException("REC-1", new MailSendException("Connection refused"));

        service.reportFailure(event("REC-1"), failure);

        DeliveryStatusEvent published = capturePublished();
        assertThat(published.eventType()).isEqualTo(DeliveryStatusEvent.EMAIL_DELIVERY_FAILED);
        assertThat(published.deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(published.errorMessage()).contains("Connection refused");
        assertThat(published.processedTime()).isEqualTo(NOW);
    }

    private void givenValidPath() {
        when(downloader.keyOf(PATH)).thenReturn(KEY);
    }

    private DeliveryStatusEvent capturePublished() {
        ArgumentCaptor<DeliveryStatusEvent> captor = ArgumentCaptor.forClass(DeliveryStatusEvent.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue();
    }

    private DeliveryStatusEvent captureOrdered(InOrder order) {
        ArgumentCaptor<DeliveryStatusEvent> captor = ArgumentCaptor.forClass(DeliveryStatusEvent.class);
        order.verify(publisher).publish(captor.capture());
        return captor.getValue();
    }

    private static EmailDeliveryEvent event(String recipientId) {
        return new EmailDeliveryEvent("EVT-1", "EMAIL_DELIVERY_REQUESTED", "SUB-1", "LN-1001", recipientId,
                "Riverbend Motors", "accounts@riverbend-motors.example.com", "SETTLEMENT",
                "Settlement Wire Statement - LN-1001", PATH, LocalDateTime.of(2026, 11, 21, 20, 29, 0));
    }
}