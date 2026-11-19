package io.github.headnose.dsp.settlement.controller.dto;

import io.github.headnose.dsp.settlement.model.AdjustmentType;
import io.github.headnose.dsp.settlement.model.PaymentMethod;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementRequestDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    // ---------- helpers ----------

    private static PaymentDto usdWire() {
        return new PaymentDto(PaymentMethod.WIRE, "USD", null, null);
    }

    private static AdjustmentDto docFee(String amount) {
        return new AdjustmentDto(AdjustmentType.DOC_FEE, new BigDecimal(amount));
    }

    /** Returns the paths of the fields that failed, e.g. "adjustments[0].amount". */
    private static Set<String> violatedPaths(SettlementRequestDto request) {
        Set<ConstraintViolation<SettlementRequestDto>> violations = validator.validate(request);
        return violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    // ---------- valid requests ----------

    @Test
    void validRequestHasNoViolations() {
        var request = new SettlementRequestDto(usdWire(),
                List.of(docFee("150.00"),
                        new AdjustmentDto(AdjustmentType.DEALER_HOLDBACK, new BigDecimal("500.00"))));

        assertThat(violatedPaths(request)).isEmpty();
    }

    @Test
    void emptyAdjustmentListIsValid() {
        assertThat(violatedPaths(new SettlementRequestDto(usdWire(), List.of()))).isEmpty();
    }

    // ---------- top-level fields ----------

    @Test
    void paymentIsRequired() {
        assertThat(violatedPaths(new SettlementRequestDto(null, List.of())))
                .containsExactly("payment");
    }

    @Test
    void adjustmentsListIsRequired() {
        assertThat(violatedPaths(new SettlementRequestDto(usdWire(), null)))
                .containsExactly("adjustments");
    }

    @Test
    void moreThanTwentyAdjustmentsIsRejected() {
        List<AdjustmentDto> many = new ArrayList<>(Collections.nCopies(21, docFee("1.00")));

        assertThat(violatedPaths(new SettlementRequestDto(usdWire(), many)))
                .containsExactly("adjustments");
    }

    // ---------- nested payment (proves @Valid cascades) ----------

    @Test
    void paymentMethodIsRequired() {
        var request = new SettlementRequestDto(new PaymentDto(null, "USD", null, null), List.of());

        assertThat(violatedPaths(request)).containsExactly("payment.paymentMethod");
    }

    @Test
    void currencyMustBeThreeUppercaseLetters() {
        assertThat(violatedPaths(new SettlementRequestDto(new PaymentDto(PaymentMethod.WIRE, "usd", null, null), List.of())))
                .containsExactly("payment.currency");
        assertThat(violatedPaths(new SettlementRequestDto(new PaymentDto(PaymentMethod.WIRE, "US", null, null), List.of())))
                .containsExactly("payment.currency");
    }

    // ---------- each adjustment (proves List<@Valid ...> cascades) ----------

    @Test
    void zeroAmountIsRejected() {
        assertThat(violatedPaths(new SettlementRequestDto(usdWire(), List.of(docFee("0.00")))))
                .containsExactly("adjustments[0].amount");
    }

    @Test
    void negativeAmountIsRejected() {
        assertThat(violatedPaths(new SettlementRequestDto(usdWire(), List.of(docFee("-50.00")))))
                .containsExactly("adjustments[0].amount");
    }

    @Test
    void moreThanTwoMeaningfulDecimalsIsRejected() {
        assertThat(violatedPaths(new SettlementRequestDto(usdWire(), List.of(docFee("10.005")))))
                .containsExactly("adjustments[0].amount");
    }

    @Test
    void trailingZerosBeyondTwoDecimalsAreRejectedAtTheApi() {
        // @Digits counts the scale as written (10.500 has scale 3), so it fails here.
        // The calculator is more lenient and strips the zeros first. The API edge being
        // stricter than the domain is safe: nothing invalid gets through.
        var request = new SettlementRequestDto(usdWire(), List.of(docFee("10.500")));

        assertThat(violatedPaths(request)).containsExactly("adjustments[0].amount");
    }

    @Test
    void adjustmentTypeIsRequired() {
        var request = new SettlementRequestDto(usdWire(),
                List.of(new AdjustmentDto(null, new BigDecimal("10.00"))));

        assertThat(violatedPaths(request)).containsExactly("adjustments[0].adjustmentType");
    }

    @Test
    void nullElementInListIsRejected() {
        List<AdjustmentDto> withNull = new ArrayList<>();
        withNull.add(null);

        // The exact path text for a list element varies, so we only check it points at index 0
        assertThat(violatedPaths(new SettlementRequestDto(usdWire(), withNull)))
                .singleElement()
                .asString()
                .startsWith("adjustments[0]");
    }
}