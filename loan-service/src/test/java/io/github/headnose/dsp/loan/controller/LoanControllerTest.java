package io.github.headnose.dsp.loan.controller;

import io.github.headnose.dsp.loan.dto.DealerDto;
import io.github.headnose.dsp.loan.dto.LoanDto;
import io.github.headnose.dsp.loan.dto.LoanStatus;
import io.github.headnose.dsp.loan.repository.LoanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LoanController.class)
class LoanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoanRepository loanRepository;

    @Test
    void getLoan_returns200AndLoan_whenLoanExists() throws Exception {
        DealerDto dealer = new DealerDto("D-TEST", "Test Dealer", "Test Bank",
                "dealer@test.example.com", "bank@test.example.com");
        LoanDto loan = new LoanDto("LN-TEST", dealer, LoanStatus.CLOSED, "USD",
                new BigDecimal("1000.50"), LocalDate.of(2026, 1, 1));

        when(loanRepository.findById("LN-TEST")).thenReturn(Optional.of(loan));

        mockMvc.perform(get("/api/v1/loans/LN-TEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanId").value("LN-TEST"))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.dealer.dealerName").value("Test Dealer"))
                .andExpect(jsonPath("$.principalAmount").value(1000.50))
                .andExpect(jsonPath("$.closingDate").value("2026-01-01"));
    }

    @Test
    void getLoan_returns404_whenLoanDoesNotExist() throws Exception {
        when(loanRepository.findById("NOPE")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/loans/NOPE"))
                .andExpect(status().isNotFound());
    }
}