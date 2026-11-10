package io.github.headnose.dsp.settlement.service;

import io.github.headnose.dsp.settlement.controller.dto.AdjustmentRequest;
import io.github.headnose.dsp.settlement.controller.dto.PaymentRequest;
import io.github.headnose.dsp.settlement.controller.dto.SettlementRequest;
import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;
import io.github.headnose.dsp.settlement.model.Adjustment;
import io.github.headnose.dsp.settlement.model.LoanInfo;
import io.github.headnose.dsp.settlement.model.PaymentInfo;
import io.github.headnose.dsp.settlement.model.SettlementInfo;
import io.github.headnose.dsp.settlement.repository.LoanSettlementRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Service
public class SettlementService {

    private final LoanSettlementRepository repository;
    private final SettlementCalculator calculator;
    private final Clock clock;

    public SettlementService(LoanSettlementRepository repository,
                             SettlementCalculator calculator,
                             Clock clock) {
        this.repository = repository;
        this.calculator = calculator;
        this.clock = clock;
    }

    /**
     * Calculates (or recalculates) the settlement for an imported loan.
     *
     * @throws LoanNotImportedException if the loan is not in settlement-service (404)
     * @throws SettlementRuleViolationException if a business rule is broken (422)
     * @throws org.springframework.dao.OptimisticLockingFailureException
     *         if another request updated the loan at the same time (409)
     */
    public LoanSettlementDocument calculate(String loanId, SettlementRequest request) {
        LoanSettlementDocument doc = repository.findById(loanId)
                .orElseThrow(() -> new LoanNotImportedException(loanId));

        LoanInfo loan = doc.getLoan();
        checkCurrencyMatchesLoan(loan, request.payment());

        List<Adjustment> adjustments = request.adjustments().stream()
                .map(AdjustmentRequest::toAdjustment)
                .toList();

        SettlementInfo settlement = calculator.calculate(loan.principalAmount(), adjustments);

        PaymentInfo payment = new PaymentInfo(
                request.payment().paymentMethod(),
                request.payment().currency(),
                loan.dealer().dealerBankName(),
                BusinessDays.nextBusinessDay(LocalDate.now(clock)));

        doc.applySettlement(payment, adjustments, settlement);
        return repository.save(doc);
    }

    /** Rule 6: the payment currency must match the loan currency (no FX conversion). */
    private static void checkCurrencyMatchesLoan(LoanInfo loan, PaymentRequest payment) {
        if (!payment.currency().equals(loan.currency())) {
            throw new SettlementRuleViolationException(
                    "Payment currency " + payment.currency()
                            + " does not match loan currency " + loan.currency());
        }
    }
}