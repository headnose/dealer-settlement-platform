package io.github.headnose.dsp.settlement.cache;

import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;
import io.github.headnose.dsp.settlement.model.Adjustment;
import io.github.headnose.dsp.settlement.model.LoanInfo;
import io.github.headnose.dsp.settlement.model.PaymentInfo;
import io.github.headnose.dsp.settlement.model.SettlementInfo;
import io.github.headnose.dsp.settlement.model.SettlementStatus;

import java.util.List;

/**
 * What the settlement detail cache stores (C6): a snapshot of the stored data, NOT the API DTO.
 * The API DTOs carry annotations for clients (PaymentDto's READ_ONLY fields are ignored when JSON
 * is read back), so a DTO round trip through Redis would lose beneficiaryBank and valueDate.
 * The model records have no Jackson annotations, so every field round-trips.
 * Only built for CALCULATED documents.
 */
public record CachedSettlementDetail(
        LoanInfo loan,
        SettlementStatus status,
        SettlementInfo settlement,
        PaymentInfo payment,
        List<Adjustment> adjustments
) {
    public static CachedSettlementDetail from(LoanSettlementDocument doc) {
        return new CachedSettlementDetail(
                doc.getLoan(),
                doc.getStatus(),
                doc.getSettlement(),
                doc.getPayment(),
                doc.getAdjustments());
    }
}