package io.github.headnose.dsp.settlement.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PathIdMdcInterceptorTest {

    private final PathIdMdcInterceptor interceptor = new PathIdMdcInterceptor(MdcKeys.LOAN_ID);

    @AfterEach
    void cleanUp() {
        MDC.clear();
    }

    @Test
    void loanIdFromThePathIsInTheMdcUntilTheRequestCompletes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("loanId", "LN-1001"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        assertThat(MDC.get(MdcKeys.LOAN_ID)).isEqualTo("LN-1001");

        interceptor.afterCompletion(request, response, new Object(), null);
        assertThat(MDC.get(MdcKeys.LOAN_ID)).isNull();
    }

    @Test
    void requestWithoutThatPathVariablePutsNothing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("submissionId", "SUB-1"));

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(MDC.get(MdcKeys.LOAN_ID)).isNull();
    }
}