package io.github.headnose.dsp.wire.repository;

import io.github.headnose.dsp.wire.MySqlTestContainerConfig;
import io.github.headnose.dsp.wire.entity.WireRecipient;
import io.github.headnose.dsp.wire.entity.WireSubmission;
import io.github.headnose.dsp.wire.model.DeliveryStatus;
import io.github.headnose.dsp.wire.model.RecipientType;
import io.github.headnose.dsp.wire.model.SubmissionStatus;
import io.github.headnose.dsp.wire.model.WireType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(MySqlTestContainerConfig.class)
class WireSubmissionRepositoryTest {

    @Autowired
    private WireSubmissionRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesDraftWithFourRecipientsAndReadsItBack() {
        WireSubmission draft = WireSubmission.draft("LN-1001", WireType.SETTLEMENT,
                "<settlementWire/>", LocalDateTime.of(2026, 11, 21, 14, 30, 25, 123_000_000));
        draft.addRecipient(RecipientType.DEALER,
                "Riverbend Motors", "accounts@riverbend-motors.example.com");
        draft.addRecipient(RecipientType.DEALER_BANK_CONTACT,
                "Riverbend Community Bank", "wires@riverbend-bank.example.com");
        draft.addRecipient(RecipientType.AUDIT_DISTRIBUTION,
                "Compliance Audit Team", "compliance-audit@example.com");
        draft.addRecipient(RecipientType.INTERNAL_OPS_DISTRIBUTION,
                "Treasury Operations", "treasury-ops@example.com");
        assertThat(draft.isNew()).isTrue();

        repository.save(draft);
        entityManager.flush();   // send the INSERTs to MySQL now
        entityManager.clear();   // forget cached objects, so the read below really hits MySQL

        WireSubmission found = repository.findById(draft.getSubmissionId()).orElseThrow();

        assertThat(found.isNew()).isFalse();
        assertThat(found.getSubmissionId()).startsWith("SUB-");
        assertThat(found.getLoanId()).isEqualTo("LN-1001");
        assertThat(found.getStatus()).isEqualTo(SubmissionStatus.DRAFT);
        assertThat(found.getCreatedTime()).isEqualTo(LocalDateTime.of(2026, 11, 21, 14, 30, 25));
        assertThat(found.getRecipients())
                .hasSize(4)
                .allSatisfy(recipient -> {
                    assertThat(recipient.getRecipientId()).startsWith("REC-");
                    assertThat(recipient.getDeliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
                    assertThat(recipient.getLastUpdated()).isNull();
                });
        assertThat(found.getRecipients())
                .extracting(WireRecipient::getRecipientType)
                .containsExactlyInAnyOrder(RecipientType.values());
    }
}