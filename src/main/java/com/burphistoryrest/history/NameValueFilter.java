/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.history;

import com.burphistoryrest.server.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A presence or name/value substring filter used for headers, cookies, and query parameters. */
public record NameValueFilter(String name, String value) {
    public NameValueFilter {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Filter name must not be blank");
        }
        name = name.trim();
        value = value == null || value.isBlank() ? null : value.trim();
    }

    public boolean hasValue() {
        return value != null;
    }

    public Map<String, Object> describe() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("valueContains", value);
        return result;
    }

    public static List<NameValueFilter> parse(List<String> values, boolean allowColon, String parameterName) {
        List<NameValueFilter> result = new ArrayList<>();
        for (String raw : values) {
            String expression = raw == null ? "" : raw.trim();
            if (expression.isEmpty()) {
                continue;
            }
            if (expression.length() > 4_096) {
                throw ApiException.badRequest(
                        "filter_too_long",
                        parameterName + " filters must not exceed 4096 characters"
                );
            }

            int equalsSeparator = expression.indexOf('=');
            int colonSeparator = allowColon ? expression.indexOf(':') : -1;
            int separator;
            if (equalsSeparator < 0) {
                separator = colonSeparator;
            } else if (colonSeparator < 0) {
                separator = equalsSeparator;
            } else {
                separator = Math.min(equalsSeparator, colonSeparator);
            }
            String name = separator < 0 ? expression : expression.substring(0, separator).trim();
            String value = separator < 0 ? null : expression.substring(separator + 1).trim();
            if (name.isBlank()) {
                throw ApiException.badRequest(
                        "invalid_filter",
                        parameterName + " must use NAME, NAME=VALUE, or NAME:VALUE"
                );
            }
            result.add(new NameValueFilter(name, value));
        }
        if (result.size() > 64) {
            throw ApiException.badRequest("too_many_filters", "Use at most 64 " + parameterName + " filters");
        }
        return List.copyOf(result);
    }
}
