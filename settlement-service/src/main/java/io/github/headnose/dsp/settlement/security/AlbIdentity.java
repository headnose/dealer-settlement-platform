package io.github.headnose.dsp.settlement.security;

import java.util.Set;

/** Who the ALB says the user is: the IdP's subject and the user's groups (e.g. FinanceOps). */
public record AlbIdentity(String subject, Set<String> groups) {
}