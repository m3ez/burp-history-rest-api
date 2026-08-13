/* Author: Supakiad S. (m3ez) - E-CQURITY (Thailand) | http://x.com/supakiad_mee */
package com.burphistoryrest.server;

import com.burphistoryrest.security.AccessTokenStore;
import com.burphistoryrest.security.ApiPrincipal;
import com.burphistoryrest.server.http.HttpHeaders;

import java.util.List;
import java.util.Optional;

public final class Auth {
    private Auth() { }

    public static Optional<ApiPrincipal> authenticate(HttpHeaders headers, AccessTokenStore tokens) {
        List<String> authorizations = headers.all("Authorization");
        List<String> apiKeys = headers.all("X-API-Key");
        if (authorizations.size() + apiKeys.size() != 1) return Optional.empty();
        String secret;
        if (!authorizations.isEmpty()) {
            String value = authorizations.getFirst().trim();
            if (!value.regionMatches(true, 0, "Bearer ", 0, 7)) return Optional.empty();
            secret = value.substring(7).trim();
        } else {
            secret = apiKeys.getFirst().trim();
        }
        return tokens.authenticate(secret);
    }
}
