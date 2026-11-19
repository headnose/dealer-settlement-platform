package io.github.headnose.dsp.settlement.controller.dto;

import io.github.headnose.dsp.settlement.model.PaymentMethod;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentDtoTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void ignoresServerFilledFieldsSentByClient() {
        String json = """
                {"paymentMethod":"WIRE","currency":"USD",
                 "beneficiaryBank":"Some Other Bank","valueDate":"2030-01-01"}
                """;

        PaymentDto dto = mapper.readValue(json, PaymentDto.class);

        assertThat(dto.paymentMethod()).isEqualTo(PaymentMethod.WIRE);
        assertThat(dto.beneficiaryBank()).isNull();
        assertThat(dto.valueDate()).isNull();
    }
}