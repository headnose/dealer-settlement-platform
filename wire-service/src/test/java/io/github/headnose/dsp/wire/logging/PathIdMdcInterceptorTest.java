package io.github.headnose.dsp.wire.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PathIdMdcInterceptorTest {

    private final PathIdMdcInterceptor interceptor = new PathIdMdcInterceptor(MdcKeys.SUBMISSION_ID);

    @AfterEach
    void cleanUp() {
        MDC.clear();
    }

    @Test
    void submissionIdFromThePathIsInTheMdcUntilTheRequestCompletes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("submissionId", "SUB-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        assertThat(MDC.get(MdcKeys.SUBMISSION_ID)).isEqualTo("SUB-1");

        interceptor.afterCompletion(request, response, new Object(), null);
        assertThat(MDC.get(MdcKeys.SUBMISSION_ID)).isNull();
    }

    @Test
    void requestWithoutThatPathVariablePutsNothing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("loanId", "LN-1"));

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(MDC.get(MdcKeys.SUBMISSION_ID)).isNull();
    }
}