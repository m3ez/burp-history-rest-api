/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.history;

import com.burphistoryrest.config.ApiSettings;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Best-effort redaction for API output. Matching/searching always uses the original Burp messages. */
public final class RedactionPolicy {
    private static final Pattern JSON_PAIR = Pattern.compile(
            "(?is)(\\\"([^\\\"]+)\\\"\\s*:\\s*)(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|true|false|null|-?\\d+(?:\\.\\d+)?)"
    );
    private static final Pattern GENERIC_PAIR = Pattern.compile(
            "(?i)(^|[&;\\s,{])([A-Za-z0-9_.\\-]{1,128})(\\s*[:=]\\s*)([^&;\\s,}]+)"
    );

    private final boolean enabled;
    private final Set<String> headerNames;
    private final Set<String> parameterNames;
    private final String replacement;

    public RedactionPolicy(ApiSettings settings) {
        this(
                settings.redactionEnabled(),
                parseNames(settings.redactedHeaderNames()),
                parseNames(settings.redactedParameterNames()),
                settings.redactionReplacement()
        );
    }

    public RedactionPolicy(boolean enabled, Set<String> headerNames, Set<String> parameterNames, String replacement) {
        this.enabled = enabled;
        this.headerNames = normalize(headerNames);
        this.parameterNames = normalize(parameterNames);
        this.replacement = replacement;
    }

    public boolean enabled() {
        return enabled;
    }

    public String replacement() {
        return replacement;
    }

    public Map<String, List<String>> headers(Map<String, List<String>> headers) {
        if (!enabled || headers == null || headers.isEmpty()) {
            return headers == null ? Map.of() : headers;
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        headers.forEach((name, values) -> result.put(
                name,
                isSensitiveHeader(name) ? values.stream().map(ignored -> replacement).toList() : List.copyOf(values)
        ));
        return result;
    }

    public List<HttpMessageMetadata.NameValue> cookies(List<HttpMessageMetadata.NameValue> pairs) {
        if (!enabled) {
            return pairs;
        }
        return pairs.stream().map(pair -> new HttpMessageMetadata.NameValue(pair.name(), replacement)).toList();
    }

    public List<HttpMessageMetadata.NameValue> parameters(List<HttpMessageMetadata.NameValue> pairs) {
        if (!enabled) {
            return pairs;
        }
        return pairs.stream().map(pair -> new HttpMessageMetadata.NameValue(
                pair.name(), isSensitiveParameter(pair.name()) ? replacement : pair.value()
        )).toList();
    }

    public String url(String value) {
        if (!enabled || value == null || value.isEmpty()) {
            return value;
        }
        try {
            URI uri = new URI(value);
            String rawQuery = uri.getRawQuery();
            if (rawQuery == null) {
                return value;
            }
            return new URI(
                    uri.getScheme(), uri.getRawAuthority(), uri.getRawPath(), redactRawQuery(rawQuery), uri.getRawFragment()
            ).toASCIIString();
        } catch (URISyntaxException exception) {
            int query = value.indexOf('?');
            return query < 0 ? value : value.substring(0, query + 1) + redactRawQuery(value.substring(query + 1));
        }
    }

    public String path(String value) {
        if (!enabled || value == null) {
            return value;
        }
        int query = value.indexOf('?');
        return query < 0 ? value : value.substring(0, query + 1) + redactRawQuery(value.substring(query + 1));
    }

    public String query(String rawQuery) {
        return !enabled || rawQuery == null ? rawQuery : redactRawQuery(rawQuery);
    }

    public String body(String body, String contentType) {
        if (!enabled || body == null || body.isEmpty()) {
            return body;
        }
        String result = redactJson(body);
        result = redactFormLike(result);
        return result;
    }

    public byte[] message(byte[] original) {
        if (!enabled || original == null || original.length == 0) {
            return original == null ? new byte[0] : original.clone();
        }
        int delimiter = find(original, new byte[]{'\r', '\n', '\r', '\n'});
        int delimiterLength = 4;
        if (delimiter < 0) {
            delimiter = find(original, new byte[]{'\n', '\n'});
            delimiterLength = 2;
        }
        if (delimiter < 0) {
            return original.clone();
        }

        String head = new String(original, 0, delimiter, StandardCharsets.ISO_8859_1);
        String lineSeparator = head.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = head.split("\\r?\\n", -1);
        List<String> rebuilt = new ArrayList<>();
        String contentType = null;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (index == 0) {
                rebuilt.add(redactStartLine(line));
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                rebuilt.add(line);
                continue;
            }
            String name = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (name.equalsIgnoreCase("Content-Type")) {
                contentType = value;
            }
            if (isSensitiveHeader(name)) {
                rebuilt.add(name + ": " + replacement);
            } else if (!name.equalsIgnoreCase("Content-Length")) {
                rebuilt.add(line);
            }
        }

        byte[] body = Arrays.copyOfRange(original, delimiter + delimiterLength, original.length);
        String redactedBody = body(new String(body, StandardCharsets.UTF_8), contentType);
        byte[] redactedBodyBytes = redactedBody.getBytes(StandardCharsets.UTF_8);
        boolean hadLength = Arrays.stream(lines).anyMatch(line -> line.regionMatches(true, 0, "Content-Length:", 0, 15));
        if (hadLength) {
            rebuilt.add("Content-Length: " + redactedBodyBytes.length);
        }
        byte[] redactedHead = String.join(lineSeparator, rebuilt).getBytes(StandardCharsets.ISO_8859_1);
        byte[] separatorBytes = lineSeparator.repeat(2).getBytes(StandardCharsets.ISO_8859_1);
        byte[] output = new byte[redactedHead.length + separatorBytes.length + redactedBodyBytes.length];
        System.arraycopy(redactedHead, 0, output, 0, redactedHead.length);
        System.arraycopy(separatorBytes, 0, output, redactedHead.length, separatorBytes.length);
        System.arraycopy(redactedBodyBytes, 0, output, redactedHead.length + separatorBytes.length, redactedBodyBytes.length);
        return output;
    }

    public Map<String, Object> describe() {
        return Map.of(
                "enabled", enabled,
                "headers", headerNames.stream().sorted().toList(),
                "parameters", parameterNames.stream().sorted().toList(),
                "replacement", replacement,
                "rawEndpointsRedacted", false
        );
    }

    private String redactStartLine(String line) {
        if (line == null || !line.contains("?")) {
            return line;
        }
        String[] parts = line.split(" ", 3);
        if (parts.length == 3 && !parts[0].startsWith("HTTP/")) {
            return parts[0] + " " + path(parts[1]) + " " + parts[2];
        }
        return line;
    }

    private String redactJson(String body) {
        Matcher matcher = JSON_PAIR.matcher(body);
        StringBuffer output = new StringBuffer(body.length());
        while (matcher.find()) {
            String name = matcher.group(2);
            if (isSensitiveParameter(name)) {
                matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group(1) + "\"" + replacement + "\""));
            }
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private String redactFormLike(String body) {
        Matcher matcher = GENERIC_PAIR.matcher(body);
        StringBuffer output = new StringBuffer(body.length());
        while (matcher.find()) {
            if (isSensitiveParameter(matcher.group(2))) {
                matcher.appendReplacement(output, Matcher.quoteReplacement(
                        matcher.group(1) + matcher.group(2) + matcher.group(3) + replacement
                ));
            }
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private String redactRawQuery(String rawQuery) {
        StringBuilder output = new StringBuilder(rawQuery.length());
        String[] expressions = rawQuery.split("&", -1);
        for (int index = 0; index < expressions.length; index++) {
            if (index > 0) {
                output.append('&');
            }
            String expression = expressions[index];
            int separator = expression.indexOf('=');
            String rawName = separator < 0 ? expression : expression.substring(0, separator);
            String name;
            try {
                name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                name = rawName;
            }
            if (separator >= 0 && isSensitiveParameter(name)) {
                output.append(rawName).append('=').append(URLEncoder.encode(replacement, StandardCharsets.UTF_8));
            } else {
                output.append(expression);
            }
        }
        return output.toString();
    }

    private boolean isSensitiveHeader(String name) {
        return name != null && headerNames.contains(name.toLowerCase(Locale.ROOT));
    }

    private boolean isSensitiveParameter(String name) {
        return name != null && parameterNames.contains(name.toLowerCase(Locale.ROOT));
    }

    private static Set<String> parseNames(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                result.add(name);
            }
        }
        return result;
    }

    private static Set<String> normalize(Set<String> names) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        names.forEach(name -> result.add(name.toLowerCase(Locale.ROOT)));
        return Set.copyOf(result);
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
}
