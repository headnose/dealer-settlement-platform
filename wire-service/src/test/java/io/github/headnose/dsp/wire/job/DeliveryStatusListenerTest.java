package io.github.headnose.dsp.wire.job;

import io.github.headnose.dsp.wire.logging.MdcKeys;
import io.github.headnose.dsp.wire.service.DeliveryStatusService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** O3: the event's ids are in the MDC while it is applied, and gone afterwards (listener threads are reused). */
@ExtendWith(MockitoExtension.class)
class DeliveryStatusListenerTest {

    @Mock
    DeliveryStatusService deliveryStatusService;

    @AfterEach
    void cleanUp() {
        MDC.clear();
    }

    @Test
    void idsFromTheEventAreInTheMdcWhileItIsApplied() {
        DeliveryStatusListener listener =
                new DeliveryStatusListener(JsonMapper.builder().build(), deliveryStatusService);
        AtomicReference<String> submissionId = new AtomicReference<>();
        AtomicReference<String> loanId = new AtomicReference<>();
        doAnswer(invocation -> {
            submissionId.set(MDC.get(MdcKeys.SUBMISSION_ID));
            loanId.set(MDC.get(MdcKeys.LOAN_ID));
            return null;
        }).when(deliveryStatusService).apply(any());

        listener.onDeliveryStatus("""
                {"eventId":"EVT-1","eventType":"EMAIL_DELIVERY_COMPLETED","submissionId":"SUB-1",\
                "loanId":"LN-1","recipientId":"REC-1","deliveryStatus":"SENT","errorMessage":null,\
                "processedTime":"2026-11-25T20:29:00"}
                """);

        assertThat(submissionId.get()).isEqualTo("SUB-1");
        assertThat(loanId.get()).isEqualTo("LN-1");
        assertThat(MDC.get(MdcKeys.SUBMISSION_ID)).isNull();
        assertThat(MDC.get(MdcKeys.LOAN_ID)).isNull();
    }
}