package io.github.headnose.dsp.wire.security;

import io.github.headnose.dsp.wire.logging.MdcKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * O3 (LG2): puts the authenticated caller (the ALB token's subject) into the MDC as userId, so every log line
 * of the request says who made it. Runs inside the security chain right before AuthorizationFilter (after
 * authentication, before the 403 decision). Built in SecurityConfig, not a @Bean (a Filter bean would also
 * run as a servlet filter). The entry is removed when the request ends, also on exceptions: Tomcat reuses
 * threads.
 */
public class UserMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userId = currentUserId();
        if (userId == null) {
            chain.doFilter(request, response);
            return;
        }
        try (MDC.MDCCloseable ignored = MDC.putCloseable(MdcKeys.USER_ID, userId)) {
            chain.doFilter(request, response);
        }
    }

    /** null when nobody is authenticated (no header, or only Spring's anonymous token). */
    static String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }
}