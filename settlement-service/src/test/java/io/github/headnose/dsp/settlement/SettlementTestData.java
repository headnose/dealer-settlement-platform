package io.github.headnose.dsp.settlement;

import io.github.headnose.dsp.settlement.model.Adjustment;
import io.github.headnose.dsp.settlement.model.AdjustmentType;
import io.github.headnose.dsp.settlement.model.DealerInfo;
import io.github.headnose.dsp.settlement.model.LoanInfo;
import io.github.headnose.dsp.settlement.model.LoanStatus;
import io.github.headnose.dsp.settlement.model.PaymentInfo;
import io.github.headnose.dsp.settlement.model.PaymentMethod;
import io.github.headnose.dsp.settlement.model.SettlementInfo;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Sample data shared by settlement tests. */
public final class SettlementTestData {

    public static final String LOAN_ID = "LN-1001";

    private SettlementTestData() {
    }

    public static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    public static Adjustment adj(AdjustmentType type, String amount) {
        return new Adjustment(type, money(amount));
    }

    public static LoanInfo closedLoan() {
        DealerInfo dealer = new DealerInfo(
                "D-100",
                "Sunrise Auto Group",
                "Sunrise Auto Group Bank",
                "dealer@example.com",
                "wireops@example.com");
        return new LoanInfo(LOAN_ID, dealer, LoanStatus.CLOSED, "USD",
                money("28500.00"), LocalDate.of(2026, 5, 10));
    }

    public static PaymentInfo wirePayment() {
        return new PaymentInfo(PaymentMethod.WIRE, "USD",
                "Sunrise Auto Group Bank", LocalDate.of(2026, 5, 11));
    }

    public static SettlementInfo settlement(String gross, String total, String net) {
        return new SettlementInfo(money(gross), money(total), money(net));
    }
}