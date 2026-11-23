package io.github.headnose.dsp.settlement.service;

import io.github.headnose.dsp.settlement.cache.CachedSettlementDetail;
import io.github.headnose.dsp.settlement.cache.SettlementDetailCache;
import io.github.headnose.dsp.settlement.controller.dto.AdjustmentDto;
import io.github.headnose.dsp.settlement.controller.dto.PaymentDto;
import io.github.headnose.dsp.settlement.controller.dto.SettlementRequestDto;
import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;
import io.github.headnose.dsp.settlement.model.*;
import io.github.headnose.dsp.settlement.repository.LoanSettlementRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class SettlementService {

    private final LoanSettlementRepository repository;
    private final SettlementCalculator calculator;
    private final Clock clock;
    private final SettlementDetailCache cache;

    public SettlementService(LoanSettlementRepository repository,
                             SettlementCalculator calculator,
                             Clock clock,
                             SettlementDetailCache cache) {
        this.repository = repository;
        this.calculator = calculator;
        this.clock = clock;
        this.cache = cache;
    }

    /**
     * Calculates (or recalculates) the settlement for an imported loan.
     *
     * @throws LoanNotImportedException if the loan is not in settlement-service (404)
     * @throws SettlementRuleViolationException if a business rule is broken (422)
     * @throws org.springframework.dao.OptimisticLockingFailureException
     *         if another request updated the loan at the same time (409)
     */
    public LoanSettlementDocument calculate(String loanId, SettlementRequestDto request) {
        LoanSettlementDocument doc = repository.findById(loanId)
                .orElseThrow(() -> new LoanNotImportedException(loanId));

        LoanInfo loan = doc.getLoan();
        checkCurrencyMatchesLoan(loan, request.payment());

        List<Adjustment> adjustments = request.adjustments().stream()
                .map(AdjustmentDto::toAdjustment)
                .toList();

        SettlementInfo settlement = calculator.calculate(loan.principalAmount(), adjustments);

        PaymentInfo payment = new PaymentInfo(
                request.payment().paymentMethod(),
                request.payment().currency(),
                loan.dealer().dealerBankName(),
                BusinessDays.nextBusinessDay(LocalDate.now(clock)));

        doc.applySettlement(payment, adjustments, settlement);
        LoanSettlementDocument saved = repository.save(doc);
        // C6: delete AFTER the write succeeded, so the next GET reads the new settlement from MongoDB.
        // A failed save (e.g. optimistic lock) changed nothing, so it evicts nothing.
        cache.evict(loanId);
        return saved;
    }

    /**
     * GET settlement detail, cache-aside (C6): Redis first; on a miss read MongoDB and fill the cache.
     * Only CALCULATED settlements are cached; the two 404 cases are never cached (C5).
     */
    public CachedSettlementDetail getSettlementDetail(String loanId) {
        Optional<CachedSettlementDetail> cached = cache.get(loanId);
        if (cached.isPresent()) {
            return cached.get();
        }
        CachedSettlementDetail detail = CachedSettlementDetail.from(getCalculatedSettlement(loanId));
        cache.put(detail);
        return detail;
    }

    public LoanSettlementDocument getCalculatedSettlement(String loanId) {
        LoanSettlementDocument doc = repository.findById(loanId)
                .orElseThrow(() -> new LoanNotImportedException(loanId));

        if (doc.getStatus() != SettlementStatus.CALCULATED) {
            throw new SettlementNotCalculatedException(loanId);
        }
        return doc;
    }

    /** Rule 6: the payment currency must match the loan currency (no FX conversion). */
    private static void checkCurrencyMatchesLoan(LoanInfo loan, PaymentDto payment) {
        if (!payment.currency().equals(loan.currency())) {
            throw new SettlementRuleViolationException(
                    "Payment currency " + payment.currency()
                            + " does not match loan currency " + loan.currency());
        }
    }
}