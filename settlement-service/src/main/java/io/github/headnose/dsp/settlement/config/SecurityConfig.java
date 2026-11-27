package io.github.headnose.dsp.settlement.config;

import io.github.headnose.dsp.settlement.security.AlbHeaderAuthenticationFilter;
import io.github.headnose.dsp.settlement.security.AlbSecurityProperties;
import io.github.headnose.dsp.settlement.security.UserMdcFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Blueprint "Authentication and Authorization", steps 3-5, URL layer.
 * Two ways in, one chain: users via the ALB's signed claims header (step 3), services via an OAuth2 Bearer
 * token from the IdP (step 5, client credentials). A request carries one or the other.
 * Two layers: this filter chain is deny-by-default (coarse), and @PreAuthorize on each controller method
 * checks the exact roles or scopes (fine-grained).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            AlbSecurityProperties albProperties,
                                            PreAuthenticatedAuthenticationProvider albAuthenticationProvider,
                                            @Qualifier("handlerExceptionResolver")
                                            HandlerExceptionResolver exceptionResolver) throws Exception {
        // Built here, NOT as a @Bean: Boot registers every Filter bean as a servlet filter too,
        // so a filter bean would run twice (once outside the security chain).
        AlbHeaderAuthenticationFilter albFilter = new AlbHeaderAuthenticationFilter(albProperties.claimsHeader());
        albFilter.setAuthenticationManager(new ProviderManager(albAuthenticationProvider));
        // Keep the authenticated user for this request only, never in an HTTP session.
        albFilter.setSecurityContextRepository(new RequestAttributeSecurityContextRepository());

        // 401/403 go through GlobalExceptionHandler, so they are ProblemDetail like every other error.
        AuthenticationEntryPoint problemDetailEntryPoint =
                (request, response, e) -> exceptionResolver.resolveException(request, response, null, e);
        AccessDeniedHandler problemDetailAccessDenied =
                (request, response, e) -> exceptionResolver.resolveException(request, response, null, e);

        http
                // Locally there is no cookie and no UI. In AWS the ALB login IS cookie-based, so a real
                // browser UI needs CSRF protection (known limitation, see README).
                .csrf(AbstractHttpConfigurer::disable)
                // Every request carries its own identity (ALB headers / bearer token): no HTTP session.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterAt(albFilter, AbstractPreAuthenticatedProcessingFilter.class)
                // O3: userId into the MDC once both authentication filters have run, before the 403 decision.
                .addFilterBefore(new UserMdcFilter(), AuthorizationFilter.class)
                // Step 5: service tokens (scope claim -> SCOPE_* authorities). Without these two handlers a bad
                // Bearer token would get Spring's own empty 401, not our ProblemDetail.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problemDetailEntryPoint)
                        .accessDeniedHandler(problemDetailAccessDenied))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemDetailEntryPoint)
                        .accessDeniedHandler(problemDetailAccessDenied))
                .authorizeHttpRequests(auth -> auth
                        // Health: load-balancer checks cannot authenticate. Metrics: k6 load tests.
                        // Prometheus: the scraper sends no user token (O1). Known limitation: open without
                        // a token; a separate internal management port is the real fix.
                        .requestMatchers("/actuator/health", "/actuator/metrics", "/actuator/metrics/**",
                                "/actuator/prometheus").permitAll()
                        // Boot's error page must not turn a real error into a 401/403.
                        .requestMatchers("/error").permitAll()
                        // API docs (springdoc): the page and the spec itself carry no data, and a browser
                        // cannot send the ALB header to load them. The endpoints they describe stay protected:
                        // "Try it out" needs a token pasted under Authorize. In AWS, turn the docs off with
                        // SPRINGDOC_APIDOCS_ENABLED=false and SPRINGDOC_SWAGGERUI_ENABLED=false.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html",
                                "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        // Anything not listed (new endpoints, other actuator paths) is denied until listed.
                        .anyRequest().denyAll());
        return http.build();
    }
}