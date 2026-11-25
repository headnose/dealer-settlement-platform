package io.github.headnose.dsp.settlement.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.AuthenticationUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import java.util.List;

/**
 * Turns verified ALB claims into a Spring Security user: subject = username, each IdP group = one role
 * (FinanceOps -> ROLE_FinanceOps, which is what hasRole('FinanceOps') checks).
 */
public class AlbUserDetailsService implements AuthenticationUserDetailsService<PreAuthenticatedAuthenticationToken> {

    static final String ROLE_PREFIX = "ROLE_";

    private static final Logger log = LoggerFactory.getLogger(AlbUserDetailsService.class);

    private final AlbClaimsVerifier verifier;

    public AlbUserDetailsService(AlbClaimsVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public UserDetails loadUserDetails(PreAuthenticatedAuthenticationToken token) {
        AlbIdentity identity;
        try {
            identity = verifier.verify((String) token.getPrincipal());
        } catch (InvalidAlbTokenException e) {
            // Reason in the log (never the token itself); the client only gets a generic 401.
            log.warn("Rejected ALB claims: {}", e.getMessage());
            throw e;
        }
        List<SimpleGrantedAuthority> authorities = identity.groups().stream()
                .map(group -> new SimpleGrantedAuthority(ROLE_PREFIX + group))
                .toList();
        return new User(identity.subject(), "N/A", authorities);
    }
}