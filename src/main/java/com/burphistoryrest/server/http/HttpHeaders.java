/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.server.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Small case-insensitive HTTP header collection. */
public final class HttpHeaders {
    private final Map<String, List<String>> values = new LinkedHashMap<>();

    public void add(String name, String value) {
        values.computeIfAbsent(normalize(name), ignored -> new ArrayList<>()).add(value);
    }

    public void set(String name, String value) {
        values.put(normalize(name), new ArrayList<>(List.of(value)));
    }

    public Optional<String> first(String name) {
        List<String> matches = values.get(normalize(name));
        return matches == null || matches.isEmpty() ? Optional.empty() : Optional.of(matches.getFirst());
    }

    public List<String> all(String name) {
        List<String> matches = values.get(normalize(name));
        return matches == null ? List.of() : List.copyOf(matches);
    }

    public boolean contains(String name) {
        return values.containsKey(normalize(name));
    }

    public Map<String, List<String>> asMap() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((key, list) -> copy.put(key, List.copyOf(list)));
        return Collections.unmodifiableMap(copy);
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
