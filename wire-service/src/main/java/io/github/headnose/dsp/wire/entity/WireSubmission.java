package io.github.headnose.dsp.wire.entity;

import io.github.headnose.dsp.wire.model.RecipientType;
import io.github.headnose.dsp.wire.model.SubmissionStatus;
import io.github.headnose.dsp.wire.model.WireType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A generated wire statement (blueprint: Wire Submission). Owns its four recipients. */
@Entity
@Table(name = "wire_submission")
public class WireSubmission implements Persistable<String> {

    @Id
    private String submissionId;

    private String loanId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private WireType wireType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private SubmissionStatus status;

    private LocalDateTime createdTime;

    @Column(columnDefinition = "TEXT")
    private String content;

    @OneToMany(mappedBy = "submission", cascade = CascadeType.PERSIST)
    private List<WireRecipient> recipients = new ArrayList<>();

    /** True until first saved or loaded; tells Spring Data to INSERT without a SELECT first. */
    @Transient
    private boolean isNew = true;

    protected WireSubmission() {
        // for JPA
    }

    /** A new DRAFT submission. createdTime is truncated to seconds to match the DATETIME column. */
    public static WireSubmission draft(String loanId, WireType wireType, String content,
                                       LocalDateTime createdTime) {
        WireSubmission submission = new WireSubmission();
        submission.submissionId = "SUB-" + UUID.randomUUID();
        submission.loanId = loanId;
        submission.wireType = wireType;
        submission.status = SubmissionStatus.DRAFT;
        submission.createdTime = createdTime.truncatedTo(ChronoUnit.SECONDS);
        submission.content = content;
        return submission;
    }

    public void addRecipient(RecipientType type, String name, String email) {
        recipients.add(new WireRecipient(this, type, name, email));
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public String getId() { return submissionId; }

    @Override
    public boolean isNew() { return isNew; }

    public String getSubmissionId() { return submissionId; }
    public String getLoanId() { return loanId; }
    public WireType getWireType() { return wireType; }
    public SubmissionStatus getStatus() { return status; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public String getContent() { return content; }
    public List<WireRecipient> getRecipients() { return List.copyOf(recipients); }
}