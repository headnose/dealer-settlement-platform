package io.github.headnose.dsp.wire.config;

import io.github.headnose.dsp.wire.security.AlbHeaderAuthenticationFilter;
import io.github.headnose.dsp.wire.security.AlbSecurityProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Blueprint "Authentication and Authorization", step 3/4, URL layer.
 * Authentication happens at the ALB; this service verifies the ALB's signed claims and enforces
 * authorization. Two layers: this filter chain is deny-by-default (coarse), and @PreAuthorize on each
 * controller method checks the exact roles (fine-grained).
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

        http
                // Locally there is no cookie and no UI. In AWS the ALB login IS cookie-based, so a real
                // browser UI needs CSRF protection (known limitation, see README).
                .csrf(AbstractHttpConfigurer::disable)
                // Every request carries its own identity (ALB headers / bearer token): no HTTP session.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterAt(albFilter, AbstractPreAuthenticatedProcessingFilter.class)
                // 401/403 go through GlobalExceptionHandler, so they are ProblemDetail like every other error.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, e) ->
                                exceptionResolver.resolveException(request, response, null, e))
                        .accessDeniedHandler((request, response, e) ->
                                exceptionResolver.resolveException(request, response, null, e)))
                .authorizeHttpRequests(auth -> auth
                        // Health: load-balancer checks cannot authenticate. Metrics: k6 load tests
                        // (known limitation; a separate internal management port is the real fix).
                        .requestMatchers("/actuator/health", "/actuator/metrics", "/actuator/metrics/**").permitAll()
                        // Boot's error page must not turn a real error into a 401/403.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        // Anything not listed (new endpoints, other actuator paths) is denied until listed.
                        .anyRequest().denyAll());
        return http.build();
    }
}