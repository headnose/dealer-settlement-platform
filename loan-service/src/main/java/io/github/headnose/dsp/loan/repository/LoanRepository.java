package io.github.headnose.dsp.loan.repository;

import io.github.headnose.dsp.loan.dto.DealerDto;
import io.github.headnose.dsp.loan.dto.LoanDto;
import io.github.headnose.dsp.loan.dto.LoanStatus;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    // Sample loans, keyed by loanId
    private final Map<String, LoanDto> loans = Map.of(
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

    public Optional<LoanDto> findById(String loanId) {
        return Optional.ofNullable(loans.get(loanId));
    }
}