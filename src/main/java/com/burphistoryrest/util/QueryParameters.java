/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.util;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class QueryParameters {
    private static final int MAX_RAW_QUERY_LENGTH = 32_768;
    private static final int MAX_PARAMETER_COUNT = 200;

    private final Map<String, List<String>> values;

    private QueryParameters(Map<String, List<String>> values) {
        this.values = values;
    }

    public static QueryParameters parse(URI uri) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return new QueryParameters(Map.of());
        }
        if (rawQuery.length() > MAX_RAW_QUERY_LENGTH) {
            throw new IllegalArgumentException("Query string is too long");
        }

        Map<String, List<String>> result = new LinkedHashMap<>();
        String[] pairs = rawQuery.split("&", -1);
        if (pairs.length > MAX_PARAMETER_COUNT) {
            throw new IllegalArgumentException("Too many query parameters");
        }

        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String rawName = separator >= 0 ? pair.substring(0, separator) : pair;
            String rawValue = separator >= 0 ? pair.substring(separator + 1) : "";
            String name = decode(rawName).toLowerCase(Locale.ROOT);
            String value = decode(rawValue);
            if (name.isBlank()) {
                throw new IllegalArgumentException("Query parameter names must not be empty");
            }
            result.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }

        Map<String, List<String>> immutable = new LinkedHashMap<>();
        result.forEach((key, list) -> immutable.put(key, List.copyOf(list)));
        return new QueryParameters(Collections.unmodifiableMap(immutable));
    }


    public static QueryParameters of(Map<String, ? extends List<String>> values) {
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, ? extends List<String>> entry : values.entrySet()) {
            String name = normalize(entry.getKey());
            if (name.isBlank()) {
                throw new IllegalArgumentException("Query parameter names must not be empty");
            }
            List<String> list = entry.getValue() == null ? List.of() : List.copyOf(entry.getValue());
            count += list.size();
            if (count > MAX_PARAMETER_COUNT) {
                throw new IllegalArgumentException("Too many query parameters");
            }
            normalized.computeIfAbsent(name, ignored -> new ArrayList<>()).addAll(list);
        }
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        normalized.forEach((key, list) -> immutable.put(key, List.copyOf(list)));
        return new QueryParameters(Collections.unmodifiableMap(immutable));
    }

    public Optional<String> first(String name) {
        List<String> matches = values.get(normalize(name));
        return matches == null || matches.isEmpty() ? Optional.empty() : Optional.of(matches.getFirst());
    }

    public String firstOrDefault(String name, String defaultValue) {
        return first(name).orElse(defaultValue);
    }

    public List<String> all(String name) {
        return values.getOrDefault(normalize(name), List.of());
    }

    public boolean has(String name) {
        return values.containsKey(normalize(name));
    }

    public Map<String, List<String>> asMap() {
        return values;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid percent-encoding in query string", exception);
        }
    }
}
