/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.server;

import com.burphistoryrest.util.JsonParser;
import com.burphistoryrest.util.QueryParameters;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Converts a structured POST /history/search JSON document into the existing query parser input. */
public final class SearchRequestParser {
    private static final Set<String> SECTIONS = Set.of("filters", "search", "sort", "pagination", "output", "sync");

    private SearchRequestParser() {
    }

    public static QueryParameters parse(byte[] body, QueryParameters urlParameters) {
        Map<String, Object> root = JsonParser.parseObject(new String(body, StandardCharsets.UTF_8));
        Map<String, List<String>> flattened = new LinkedHashMap<>();
        flattenDirect(root, flattened);
        flattenSection(root, "filters", flattened, Map.of());
        flattenSection(root, "search", flattened, Map.of(
                "mode", "keyword_mode", "location", "search_in", "caseSensitive", "case_sensitive"
        ));
        flattenSection(root, "sort", flattened, Map.of("field", "sort", "by", "sort"));
        flattenSection(root, "pagination", flattened, Map.of("pageSize", "page_size", "perPage", "per_page"));
        flattenSection(root, "output", flattened, Map.of());
        flattenSection(root, "sync", flattened, Map.of("afterId", "after_id", "beforeId", "before_id"));
        urlParameters.asMap().forEach((key, values) -> flattened.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(values));
        return QueryParameters.of(flattened);
    }

    private static void flattenDirect(Map<String, Object> root, Map<String, List<String>> output) {
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            if (!SECTIONS.contains(entry.getKey())) {
                add(output, normalize(entry.getKey()), entry.getValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void flattenSection(
            Map<String, Object> root,
            String section,
            Map<String, List<String>> output,
            Map<String, String> aliases
    ) {
        Object value = root.get(section);
        if (value == null) return;
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw ApiException.badRequest("invalid_search_body", section + " must be a JSON object");
        }
        Map<String, Object> map = (Map<String, Object>) rawMap;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String name = aliases.getOrDefault(entry.getKey(), normalize(entry.getKey()));
            add(output, name, entry.getValue());
        }
    }

    private static void add(Map<String, List<String>> output, String name, Object value) {
        if (value == null) return;
        if (value instanceof Map<?, ?>) {
            throw ApiException.badRequest("invalid_search_body", "Nested objects are not supported for field " + name);
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) add(output, name, element);
            return;
        }
        String string = value instanceof Boolean || value instanceof Number ? String.valueOf(value) : value.toString();
        output.computeIfAbsent(name, ignored -> new ArrayList<>()).add(string);
    }

    private static String normalize(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (Character.isUpperCase(c)) {
                if (index > 0) result.append('_');
                result.append(Character.toLowerCase(c));
            } else {
                result.append(Character.toLowerCase(c));
            }
        }
        return result.toString().toLowerCase(Locale.ROOT);
    }
}
