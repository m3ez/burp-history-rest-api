/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.security;

import java.time.Instant;
import java.util.Set;

public record ApiPrincipal(String id, String label, Set<AccessScope> scopes, Instant expiresAt) {
    public ApiPrincipal {
        scopes = Set.copyOf(scopes);
    }

    public boolean has(AccessScope scope) {
        return scopes.contains(scope);
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
