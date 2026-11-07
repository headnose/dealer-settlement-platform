package io.github.headnose.dsp.settlement.model;

import java.time.LocalDate;

public record PaymentInfo(
        PaymentMethod method,
        String currency,
        String beneficiaryBank,   // filled from the loan's dealerBankName, not entered by the user
        LocalDate valueDate       // the date the money should arrive
) {}