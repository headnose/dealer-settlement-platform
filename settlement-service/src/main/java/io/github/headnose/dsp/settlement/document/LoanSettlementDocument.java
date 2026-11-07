package io.github.headnose.dsp.settlement.document;

import io.github.headnose.dsp.settlement.model.Adjustment;
import io.github.headnose.dsp.settlement.model.LoanInfo;
import io.github.headnose.dsp.settlement.model.PaymentInfo;
import io.github.headnose.dsp.settlement.model.SettlementInfo;
import io.github.headnose.dsp.settlement.model.SettlementStatus;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "loan_settlements")
public class LoanSettlementDocument {

    @Id
    private String loanId;

    private LoanInfo loan;
    private SettlementStatus status;
    private List<Adjustment> adjustments = new ArrayList<>();
    private PaymentInfo payment;          // null until settlement is calculated
    private SettlementInfo settlement;    // null until settlement is calculated

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    // Used by Spring Data when reading from MongoDB
    protected LoanSettlementDocument() {
    }

    // Used by our code when a loan is first imported
    public static LoanSettlementDocument imported(LoanInfo loan) {
        LoanSettlementDocument doc = new LoanSettlementDocument();
        doc.loanId = loan.loanId();
        doc.loan = loan;
        doc.status = SettlementStatus.IMPORTED;
        return doc;
    }

    public String getLoanId() { return loanId; }
    public LoanInfo getLoan() { return loan; }
    public SettlementStatus getStatus() { return status; }
    public List<Adjustment> getAdjustments() { return adjustments; }
    public PaymentInfo getPayment() { return payment; }
    public SettlementInfo getSettlement() { return settlement; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}