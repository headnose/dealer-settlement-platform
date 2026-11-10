package io.github.headnose.dsp.settlement.service;

import io.github.headnose.dsp.settlement.model.Adjustment;
import io.github.headnose.dsp.settlement.model.SettlementInfo;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Pure settlement math. No I/O, no Spring dependencies beyond @Component
 * (so the service can inject it). Unit-tested with plain JUnit.
 */
@Component
public class SettlementCalculator {

    private static final int MONEY_SCALE = 2;

    /**
     * @param grossAmount the loan's principal amount (from import)
     * @param adjustments reductions to apply; may be empty, must not be null
     * @return gross, total adjustment and net, all at scale 2
     * @throws IllegalArgumentException if any adjustment amount is null, <= 0,
     *         or has more than 2 meaningful decimal places
     * @throws SettlementRuleViolationException if the net would be negative
     */
    public SettlementInfo calculate(BigDecimal grossAmount, List<Adjustment> adjustments) {
        Objects.requireNonNull(grossAmount, "grossAmount");
        Objects.requireNonNull(adjustments, "adjustments");

        for (Adjustment adjustment : adjustments) {
            validateAmount(adjustment);
        }

        BigDecimal totalAdjustment = BigDecimal.ZERO;
        for (Adjustment adjustment : adjustments) {
            totalAdjustment = totalAdjustment.add(adjustment.amount());
        }

        BigDecimal netSettlement = grossAmount.subtract(totalAdjustment);

        if (netSettlement.signum() < 0) {
            throw new SettlementRuleViolationException(
                    "Adjustments (" + totalAdjustment.toPlainString()
                            + ") exceed gross amount (" + grossAmount.toPlainString()
                            + "); net settlement would be negative: "
                            + netSettlement.toPlainString());
        }

        return new SettlementInfo(
                toMoney(grossAmount),
                toMoney(totalAdjustment),
                toMoney(netSettlement));
    }

    private static void validateAmount(Adjustment adjustment) {
        BigDecimal amount = adjustment.amount();

        if (amount == null) {
            throw new IllegalArgumentException(
                    "Adjustment amount is required for " + adjustment.type());
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Adjustment amount must be positive: "
                            + adjustment.type() + " = " + amount.toPlainString());
        }
        if (amount.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw new IllegalArgumentException(
                    "Adjustment amount has more than " + MONEY_SCALE + " decimal places: "
                            + adjustment.type() + " = " + amount.toPlainString());
        }
    }

    private static BigDecimal toMoney(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }
}