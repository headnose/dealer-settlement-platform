package io.github.headnose.dsp.settlement.service;

import io.github.headnose.dsp.settlement.model.Adjustment;
import io.github.headnose.dsp.settlement.model.AdjustmentType;
import io.github.headnose.dsp.settlement.model.SettlementInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementCalculatorTest {

    private final SettlementCalculator calculator = new SettlementCalculator();

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static Adjustment adj(AdjustmentType type, String amount) {
        return new Adjustment(type, money(amount));
    }

    // ---------- happy paths ----------

    @Test
    void subtractsAdjustmentsFromGross() {
        SettlementInfo result = calculator.calculate(money("28500.00"), List.of(
                adj(AdjustmentType.DEALER_HOLDBACK, "500.00"),
                adj(AdjustmentType.DOC_FEE, "150.00")));

        // isEqualTo on BigDecimal checks value AND scale, on purpose
        assertThat(result.grossAmount()).isEqualTo(money("28500.00"));
        assertThat(result.totalAdjustment()).isEqualTo(money("650.00"));
        assertThat(result.netSettlement()).isEqualTo(money("27850.00"));
    }

    @Test
    void noAdjustmentsMeansNetEqualsGross() {
        SettlementInfo result = calculator.calculate(money("15000.00"), List.of());

        assertThat(result.totalAdjustment()).isEqualTo(money("0.00"));
        assertThat(result.netSettlement()).isEqualTo(money("15000.00"));
    }

    @Test
    void normalizesAllAmountsToTwoDecimalPlaces() {
        SettlementInfo result = calculator.calculate(money("28500"), List.of(
                adj(AdjustmentType.TITLE_FEE, "75.5")));

        assertThat(result.grossAmount()).isEqualTo(money("28500.00"));
        assertThat(result.totalAdjustment()).isEqualTo(money("75.50"));
        assertThat(result.netSettlement()).isEqualTo(money("28424.50"));
    }

    @Test
    void allowsSameAdjustmentTypeMoreThanOnce() {
        SettlementInfo result = calculator.calculate(money("10000.00"), List.of(
                adj(AdjustmentType.DISPUTED_ITEM, "100.00"),
                adj(AdjustmentType.DISPUTED_ITEM, "200.50")));

        assertThat(result.totalAdjustment()).isEqualTo(money("300.50"));
        assertThat(result.netSettlement()).isEqualTo(money("9699.50"));
    }

    @Test
    void netOfExactlyZeroIsAllowed() {
        SettlementInfo result = calculator.calculate(money("1000.00"), List.of(
                adj(AdjustmentType.OTHER, "1000.00")));

        assertThat(result.netSettlement()).isEqualTo(money("0.00"));
    }

    @Test
    void trailingZerosBeyondTwoDecimalsAreNotRejected() {
        // "10.500" has scale 3 but no real third decimal, so it's accepted
        SettlementInfo result = calculator.calculate(money("100.00"), List.of(
                adj(AdjustmentType.DOC_FEE, "10.500")));

        assertThat(result.totalAdjustment()).isEqualTo(money("10.50"));
    }

    // ---------- business rule violation (422) ----------

    @Test
    void rejectsNegativeNet() {
        assertThatThrownBy(() -> calculator.calculate(money("1000.00"), List.of(
                adj(AdjustmentType.OTHER, "1000.01"))))
                .isInstanceOf(SettlementRuleViolationException.class)
                .hasMessageContaining("negative");
    }

    // ---------- invalid input (programmer error / 400 upstream) ----------

    @Test
    void rejectsZeroAmount() {
        assertThatThrownBy(() -> calculator.calculate(money("1000.00"), List.of(
                adj(AdjustmentType.DOC_FEE, "0.00"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> calculator.calculate(money("1000.00"), List.of(
                adj(AdjustmentType.DOC_FEE, "-50.00"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMoreThanTwoMeaningfulDecimals() {
        assertThatThrownBy(() -> calculator.calculate(money("1000.00"), List.of(
                adj(AdjustmentType.DOC_FEE, "10.005"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullAmount() {
        assertThatThrownBy(() -> calculator.calculate(money("1000.00"), List.of(
                new Adjustment(AdjustmentType.DOC_FEE, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}