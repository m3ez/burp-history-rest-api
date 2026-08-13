/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.List;

public enum AccessScope {
    HISTORY_READ("history:read"),
    HISTORY_RAW("history:raw"),
    HISTORY_EVENTS("history:events"),
    METRICS_READ("metrics:read"),
    AUDIT_READ("audit:read");

    private final String wireName;

    AccessScope(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static List<String> wireNames() {
        return Arrays.stream(values()).map(AccessScope::wireName).toList();
    }

    public static AccessScope parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(scope -> scope.wireName.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown access scope: " + value));
    }
}
