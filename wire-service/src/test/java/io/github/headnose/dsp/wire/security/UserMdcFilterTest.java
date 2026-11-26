package io.github.headnose.dsp.wire.security;

import io.github.headnose.dsp.wire.logging.MdcKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserMdcFilterTest {

    private final UserMdcFilter filter = new UserMdcFilter();

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void userIdIsInTheMdcDuringTheRequestAndRemovedAfterIt() throws Exception {
        authenticate("dev-admin");
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (request, response) -> seen.set(MDC.get(MdcKeys.USER_ID));

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isEqualTo("dev-admin");
        assertThat(MDC.get(MdcKeys.USER_ID)).isNull();
    }

    @Test
    void userIdIsRemovedWhenTheRequestThrows() {
        authenticate("dev-admin");
        FilterChain chain = (request, response) -> {
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(MdcKeys.USER_ID)).isNull();
    }

    @Test
    void anonymousRequestGetsNoUserId() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        AtomicReference<String> seen = new AtomicReference<>("not called");
        FilterChain chain = (request, response) -> seen.set(MDC.get(MdcKeys.USER_ID));

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isNull();
    }

    private static void authenticate(String name) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(name, null, List.of()));
    }
}