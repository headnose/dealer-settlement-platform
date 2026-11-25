package io.github.headnose.dsp.settlement.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;

/**
 * Blueprint step 3: reads the identity the ALB already authenticated. This filter does no OIDC itself;
 * it hands the raw signed claims to the authentication provider, which verifies them.
 * No header = no authentication attempt (the URL rules then answer 401).
 */
public class AlbHeaderAuthenticationFilter extends AbstractPreAuthenticatedProcessingFilter {

    private final String claimsHeader;

    public AlbHeaderAuthenticationFilter(String claimsHeader) {
        this.claimsHeader = claimsHeader;
    }

    @Override
    protected Object getPreAuthenticatedPrincipal(HttpServletRequest request) {
        String token = request.getHeader(claimsHeader);
        return (token == null || token.isBlank()) ? null : token;
    }

    /** Nothing secret travels separately: the proof is the signature inside the token. */
    @Override
    protected Object getPreAuthenticatedCredentials(HttpServletRequest request) {
        return "N/A";
    }
}