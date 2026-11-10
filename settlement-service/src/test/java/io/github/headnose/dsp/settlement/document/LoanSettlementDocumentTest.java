package io.github.headnose.dsp.settlement.document;

import io.github.headnose.dsp.settlement.model.Adjustment;
import io.github.headnose.dsp.settlement.model.AdjustmentType;
import io.github.headnose.dsp.settlement.model.SettlementStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.github.headnose.dsp.settlement.SettlementTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanSettlementDocumentTest {

    @Test
    void applySettlementRecordsResultAndMarksCalculated() {
        LoanSettlementDocument doc = LoanSettlementDocument.imported(closedLoan());

        doc.applySettlement(wirePayment(),
                List.of(adj(AdjustmentType.DEALER_HOLDBACK, "500.00"),
                        adj(AdjustmentType.DOC_FEE, "150.00")),
                settlement("28500.00", "650.00", "27850.00"));

        assertThat(doc.getStatus()).isEqualTo(SettlementStatus.CALCULATED);
        assertThat(doc.getPayment()).isEqualTo(wirePayment());
        assertThat(doc.getSettlement()).isEqualTo(settlement("28500.00", "650.00", "27850.00"));
        assertThat(doc.getAdjustments()).hasSize(2);
    }

    @Test
    void recalculatingReplacesPreviousAdjustments() {
        LoanSettlementDocument doc = LoanSettlementDocument.imported(closedLoan());
        doc.applySettlement(wirePayment(),
                List.of(adj(AdjustmentType.DEALER_HOLDBACK, "500.00"),
                        adj(AdjustmentType.DOC_FEE, "150.00")),
                settlement("28500.00", "650.00", "27850.00"));

        doc.applySettlement(wirePayment(),
                List.of(adj(AdjustmentType.TITLE_FEE, "75.00")),
                settlement("28500.00", "75.00", "28425.00"));

        assertThat(doc.getAdjustments())
                .containsExactly(adj(AdjustmentType.TITLE_FEE, "75.00"));
        assertThat(doc.getSettlement().netSettlement()).isEqualTo(money("28425.00"));
    }

    @Test
    void laterChangesToCallersListDoNotAffectDocument() {
        LoanSettlementDocument doc = LoanSettlementDocument.imported(closedLoan());
        List<Adjustment> callersList = new ArrayList<>();
        callersList.add(adj(AdjustmentType.DOC_FEE, "150.00"));

        doc.applySettlement(wirePayment(), callersList,
                settlement("28500.00", "150.00", "28350.00"));
        callersList.add(adj(AdjustmentType.OTHER, "999.00"));   // caller changes its own list afterwards

        assertThat(doc.getAdjustments()).hasSize(1);
    }

    @Test
    void adjustmentsCannotBeModifiedThroughGetter() {
        LoanSettlementDocument doc = LoanSettlementDocument.imported(closedLoan());

        assertThatThrownBy(() -> doc.getAdjustments().add(adj(AdjustmentType.OTHER, "1.00")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}