package io.github.headnose.dsp.wire.logging;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;

/**
 * O3: copies the named path variables (e.g. {submissionId}) into the MDC under the same name, so every log
 * line of the request says which submission it is about. preHandle runs after the route is matched and before
 * the controller (so also before @PreAuthorize: a 403 line has the id too); afterCompletion runs after
 * GlobalExceptionHandler, so error lines have it as well. Values are user input: the ECS file escapes them,
 * the text console does not.
 */
public class PathIdMdcInterceptor implements HandlerInterceptor {

    private final List<String> names;

    public PathIdMdcInterceptor(String... names) {
        this.names = List.of(names);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables) {
            for (String name : names) {
                if (variables.get(name) instanceof String value) {
                    MDC.put(name, value);
                }
            }
        }
        return true;
    }

    /** Always called once preHandle returned true, also when the request failed. Tomcat reuses threads. */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        names.forEach(MDC::remove);
    }
}