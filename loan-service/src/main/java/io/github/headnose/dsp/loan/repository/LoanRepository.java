package io.github.headnose.dsp.loan.repository;

import io.github.headnose.dsp.loan.dto.DealerDto;
import io.github.headnose.dsp.loan.dto.LoanDto;
import io.github.headnose.dsp.loan.dto.LoanStatus;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Repository
public class LoanRepository {

    // Two fictional dealers (example.com is reserved for docs/testing)
    private static final DealerDto RIVERBEND = new DealerDto(
            "D-100",
            "Riverbend Motors",
            "Riverbend Community Bank",
            "accounts@riverbend-motors.example.com",
            "wires@riverbend-bank.example.com"
    );

    private static final DealerDto SUMMIT = new DealerDto(
            "D-200",
            "Summit Auto Group",
            "Summit Valley Bank",
            "finance@summit-auto.example.com",
            "wireops@summit-valley-bank.example.com"
    );

    // Demo loans: LN-5001 to LN-5200, all CLOSED. Each loan can be sent only once, so the five hand-written
    // loans below were used up after a few end-to-end runs. Generated, not random: the same id always
    // returns the same loan (dealer alternates, amount and closing date vary).
    static final int DEMO_FIRST = 5001;
    static final int DEMO_COUNT = 200;
    private static final LocalDate DEMO_FIRST_CLOSING_DATE = LocalDate.of(2026, 10, 1);

    // Built once, then read-only (Map.copyOf): safe to read from many request threads.
    private final Map<String, LoanDto> loans;

    public LoanRepository() {
        Map<String, LoanDto> all = new HashMap<>(namedLoans());
        for (int n = 0; n < DEMO_COUNT; n++) {
            String loanId = "LN-" + (DEMO_FIRST + n);
            DealerDto dealer = n % 2 == 0 ? RIVERBEND : SUMMIT;
            BigDecimal principal = new BigDecimal("15000.00")
                    .add(new BigDecimal("137.25").multiply(BigDecimal.valueOf(n)));
            LocalDate closingDate = DEMO_FIRST_CLOSING_DATE.plusDays(n % 28);
            all.put(loanId, new LoanDto(loanId, dealer, LoanStatus.CLOSED, "USD", principal, closingDate));
        }
        loans = Map.copyOf(all);
    }

    // Hand-written sample loans, one per case the import handles (CLOSED, ACTIVE, CANCELLED)
    private static Map<String, LoanDto> namedLoans() {
        return Map.of(
                "LN-1001", new LoanDto("LN-1001", RIVERBEND, LoanStatus.CLOSED, "USD",
                        new BigDecimal("28500.00"), LocalDate.of(2026, 10, 15)),
                "LN-1002", new LoanDto("LN-1002", RIVERBEND, LoanStatus.CLOSED, "USD",
                        new BigDecimal("34250.75"), LocalDate.of(2026, 10, 15)),
                "LN-1003", new LoanDto("LN-1003", RIVERBEND, LoanStatus.ACTIVE, "USD",
                        new BigDecimal("19999.99"), LocalDate.of(2026, 10, 20)),
                "LN-2001", new LoanDto("LN-2001", SUMMIT, LoanStatus.CLOSED, "USD",
                        new BigDecimal("41000.00"), LocalDate.of(2026, 10, 18)),
                "LN-2002", new LoanDto("LN-2002", SUMMIT, LoanStatus.CANCELLED, "USD",
                        new BigDecimal("15500.00"), LocalDate.of(2026, 10, 18))
        );
    }

    public Optional<LoanDto> findById(String loanId) {
        return Optional.ofNullable(loans.get(loanId));
    }
}