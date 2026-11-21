package io.github.headnose.dsp.wire.entity;

import io.github.headnose.dsp.wire.model.DeliveryStatus;
import io.github.headnose.dsp.wire.model.RecipientType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/** One notification recipient of a wire (blueprint: Wire Recipient). Created via WireSubmission.addRecipient. */
@Entity
@Table(name = "wire_recipient")
public class WireRecipient {

    @Id
    private String recipientId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id")
    private WireSubmission submission;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private RecipientType recipientType;

    private String name;

    private String email;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private DeliveryStatus deliveryStatus;

    private LocalDateTime lastUpdated;

    protected WireRecipient() {
        // for JPA
    }

    /** Package-private: only WireSubmission creates recipients, so the link is always set. */
    WireRecipient(WireSubmission submission, RecipientType recipientType, String name, String email) {
        this.recipientId = "REC-" + UUID.randomUUID();
        this.submission = submission;
        this.recipientType = recipientType;
        this.name = name;
        this.email = email;
        this.deliveryStatus = DeliveryStatus.PENDING;
        this.lastUpdated = null;
    }

    public String getRecipientId() { return recipientId; }
    public RecipientType getRecipientType() { return recipientType; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public DeliveryStatus getDeliveryStatus() { return deliveryStatus; }
    public LocalDateTime getLastUpdated() { return lastUpdated; }
}