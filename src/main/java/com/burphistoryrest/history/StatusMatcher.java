/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.history;

import com.burphistoryrest.server.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class StatusMatcher {
    private final List<Range> ranges;
    private final boolean matchNoResponse;

    private StatusMatcher(List<Range> ranges, boolean matchNoResponse) {
        this.ranges = List.copyOf(ranges);
        this.matchNoResponse = matchNoResponse;
    }

    public static StatusMatcher any() {
        return new StatusMatcher(List.of(), true);
    }

    public static StatusMatcher parse(List<String> rawValues) {
        if (rawValues.isEmpty()) {
            return any();
        }

        List<Range> ranges = new ArrayList<>();
        boolean matchNoResponse = false;
        for (String rawValue : rawValues) {
            for (String rawToken : rawValue.split(",")) {
                String token = rawToken.trim().toLowerCase(Locale.ROOT);
                if (token.isEmpty()) {
                    continue;
                }
                if (token.equals("none") || token.equals("no-response") || token.equals("no_response")) {
                    matchNoResponse = true;
                    continue;
                }
                if (token.matches("[1-9]xx")) {
                    int hundreds = token.charAt(0) - '0';
                    ranges.add(new Range(hundreds * 100, hundreds * 100 + 99));
                    continue;
                }
                if (token.matches("\\d{3}-\\d{3}")) {
                    String[] endpoints = token.split("-", 2);
                    int minimum = parseCode(endpoints[0]);
                    int maximum = parseCode(endpoints[1]);
                    if (minimum > maximum) {
                        throw ApiException.badRequest("invalid_status", "Status range minimum exceeds maximum: " + token);
                    }
                    ranges.add(new Range(minimum, maximum));
                    continue;
                }
                if (token.matches("\\d{3}")) {
                    int status = parseCode(token);
                    ranges.add(new Range(status, status));
                    continue;
                }
                throw ApiException.badRequest(
                        "invalid_status",
                        "Invalid status filter '" + rawToken + "'. Use 200, 2xx, 200-299, or none"
                );
            }
        }

        if (ranges.isEmpty() && !matchNoResponse) {
            throw ApiException.badRequest("invalid_status", "The status filter did not contain any values");
        }
        return new StatusMatcher(ranges, matchNoResponse);
    }

    public boolean matches(Integer status) {
        if (ranges.isEmpty() && matchNoResponse) {
            return true;
        }
        if (status == null) {
            return matchNoResponse;
        }
        return ranges.stream().anyMatch(range -> range.contains(status));
    }

    public boolean isAny() {
        return ranges.isEmpty() && matchNoResponse;
    }

    private static int parseCode(String value) {
        int status = Integer.parseInt(value);
        if (status < 100 || status > 999) {
            throw ApiException.badRequest("invalid_status", "HTTP status must be between 100 and 999");
        }
        return status;
    }

    private record Range(int minimum, int maximum) {
        private boolean contains(int value) {
            return value >= minimum && value <= maximum;
        }
    }
}
