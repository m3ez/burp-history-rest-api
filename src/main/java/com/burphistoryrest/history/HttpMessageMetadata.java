/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.history;

import burp.api.montoya.http.message.HttpMessage;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lightweight structured view produced from exact Montoya HTTP message bytes. */
public final class HttpMessageMetadata {
    private final String startLine;
    private final Map<String, List<String>> headers;
    private final Map<String, List<String>> normalizedHeaders;
    private final byte[] messageBytes;
    private final int bodyStart;

    private HttpMessageMetadata(String startLine, Map<String, List<String>> headers, byte[] messageBytes, int bodyStart) {
        this.startLine = startLine;
        this.headers = immutableDeepCopy(headers);
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        headers.forEach((name, values) -> normalized
                .computeIfAbsent(name.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                .addAll(values));
        this.normalizedHeaders = immutableDeepCopy(normalized);
        this.messageBytes = messageBytes.clone();
        this.bodyStart = Math.max(0, Math.min(bodyStart, messageBytes.length));
    }

    public static HttpMessageMetadata parse(HttpMessage message) {
        if (message == null) {
            return empty();
        }
        try {
            return parse(message.toByteArray().getBytes());
        } catch (RuntimeException exception) {
            return empty();
        }
    }

    public static HttpMessageMetadata parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return empty();
        }
        int delimiter = find(bytes, new byte[]{'\r', '\n', '\r', '\n'});
        int delimiterLength = 4;
        if (delimiter < 0) {
            delimiter = find(bytes, new byte[]{'\n', '\n'});
            delimiterLength = 2;
        }
        int headEnd = delimiter < 0 ? bytes.length : delimiter;
        int bodyStart = delimiter < 0 ? bytes.length : Math.min(bytes.length, delimiter + delimiterLength);
        String head = new String(bytes, 0, headEnd, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\\r?\\n", -1);
        String startLine = lines.length == 0 ? "" : lines[0];
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String previousName = null;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if ((line.startsWith(" ") || line.startsWith("\t")) && previousName != null) {
                List<String> values = headers.get(previousName);
                int last = values.size() - 1;
                values.set(last, values.get(last) + " " + line.trim());
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String name = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            previousName = name;
        }
        return new HttpMessageMetadata(startLine, headers, bytes, bodyStart);
    }

    public String startLine() {
        return startLine;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public List<String> headerValues(String name) {
        return normalizedHeaders.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    public String firstHeader(String name) {
        List<String> values = headerValues(name);
        return values.isEmpty() ? null : values.getFirst();
    }

    public int bodyLength() {
        return messageBytes.length - bodyStart;
    }

    public byte[] bodyBytes() {
        return Arrays.copyOfRange(messageBytes, bodyStart, messageBytes.length);
    }

    public String bodyText() {
        return new String(messageBytes, bodyStart, bodyLength(), StandardCharsets.UTF_8);
    }

    public String bodyPreview(int maximumCharacters) {
        String value = bodyText();
        if (value.length() <= maximumCharacters) {
            return value;
        }
        return value.substring(0, maximumCharacters);
    }

    public List<NameValue> requestCookies() {
        List<NameValue> result = new ArrayList<>();
        for (String header : headerValues("cookie")) {
            for (String segment : header.split(";")) {
                NameValue pair = parsePair(segment, '=');
                if (pair != null) {
                    result.add(pair);
                }
            }
        }
        return List.copyOf(result);
    }

    public List<NameValue> responseCookies() {
        List<NameValue> result = new ArrayList<>();
        for (String header : headerValues("set-cookie")) {
            String first = header.split(";", 2)[0];
            NameValue pair = parsePair(first, '=');
            if (pair != null) {
                result.add(pair);
            }
        }
        return List.copyOf(result);
    }

    public static List<NameValue> queryParameters(HttpRequest request) {
        if (request == null) {
            return List.of();
        }
        String query;
        try {
            query = request.query();
        } catch (RuntimeException exception) {
            return List.of();
        }
        if (query == null || query.isEmpty()) {
            return List.of();
        }
        List<NameValue> result = new ArrayList<>();
        for (String expression : query.split("&", -1)) {
            if (expression.isEmpty()) {
                continue;
            }
            int separator = expression.indexOf('=');
            String rawName = separator < 0 ? expression : expression.substring(0, separator);
            String rawValue = separator < 0 ? "" : expression.substring(separator + 1);
            result.add(new NameValue(decode(rawName), decode(rawValue)));
        }
        return List.copyOf(result);
    }

    public static List<Map<String, String>> pairsAsMaps(List<NameValue> pairs) {
        return pairs.stream().map(pair -> Map.of("name", pair.name(), "value", pair.value())).toList();
    }

    public static Map<String, String> pairsAsMap(List<NameValue> pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (NameValue pair : pairs) {
            result.put(pair.name(), pair.value());
        }
        return result;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }

    private static NameValue parsePair(String expression, char separatorCharacter) {
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int separator = trimmed.indexOf(separatorCharacter);
        String name = separator < 0 ? trimmed : trimmed.substring(0, separator).trim();
        String value = separator < 0 ? "" : trimmed.substring(separator + 1).trim();
        return name.isEmpty() ? null : new NameValue(name, value);
    }

    private static int find(byte[] source, byte[] needle) {
        outer:
        for (int index = 0; index <= source.length - needle.length; index++) {
            for (int part = 0; part < needle.length; part++) {
                if (source[index + part] != needle[part]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    private static HttpMessageMetadata empty() {
        return new HttpMessageMetadata("", Map.of(), new byte[0], 0);
    }

    private static Map<String, List<String>> immutableDeepCopy(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((name, values) -> result.put(name, List.copyOf(values)));
        return Collections.unmodifiableMap(result);
    }

    public record NameValue(String name, String value) {
        public NameValue {
            name = name == null ? "" : name;
            value = value == null ? "" : value;
        }
    }
}
