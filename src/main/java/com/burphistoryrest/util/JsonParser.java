/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.util;

import com.burphistoryrest.server.ApiException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict JSON parser used only for bounded local API request bodies. */
public final class JsonParser {
    private final String input;
    private int index;
    private int depth;

    private JsonParser(String input) {
        this.input = input;
    }

    public static Object parse(String input) {
        if (input == null) {
            throw ApiException.badRequest("invalid_json", "JSON body is missing");
        }
        JsonParser parser = new JsonParser(input);
        Object value = parser.value();
        parser.whitespace();
        if (parser.index != input.length()) {
            throw parser.error("Unexpected characters after JSON value");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String input) {
        Object value = parse(input);
        if (!(value instanceof Map<?, ?> map)) {
            throw ApiException.badRequest("invalid_json", "JSON request body must be an object");
        }
        return (Map<String, Object>) map;
    }

    private Object value() {
        whitespace();
        if (index >= input.length()) throw error("Unexpected end of JSON");
        if (++depth > 32) throw error("JSON nesting exceeds 32 levels");
        try {
            char c = input.charAt(index);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || Character.isDigit(c)) yield number();
                    throw error("Unexpected JSON token");
                }
            };
        } finally {
            depth--;
        }
    }

    private Map<String, Object> object() {
        expect('{');
        whitespace();
        Map<String, Object> result = new LinkedHashMap<>();
        if (consume('}')) return result;
        while (true) {
            whitespace();
            if (index >= input.length() || input.charAt(index) != '"') throw error("Object key must be a string");
            String key = string();
            whitespace();
            expect(':');
            if (result.putIfAbsent(key, value()) != null) throw error("Duplicate object key: " + key);
            whitespace();
            if (consume('}')) return result;
            expect(',');
        }
    }

    private List<Object> array() {
        expect('[');
        whitespace();
        List<Object> result = new ArrayList<>();
        if (consume(']')) return result;
        while (true) {
            if (result.size() >= 1_000) throw error("JSON arrays may contain at most 1000 values");
            result.add(value());
            whitespace();
            if (consume(']')) return result;
            expect(',');
        }
    }

    private String string() {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (index < input.length()) {
            char c = input.charAt(index++);
            if (c == '"') return result.toString();
            if (c == '\\') {
                if (index >= input.length()) throw error("Unterminated JSON escape");
                char escaped = input.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(unicode());
                    default -> throw error("Invalid JSON escape");
                }
            } else {
                if (c < 0x20) throw error("Control character in JSON string");
                result.append(c);
            }
        }
        throw error("Unterminated JSON string");
    }

    private char unicode() {
        if (index + 4 > input.length()) throw error("Incomplete Unicode escape");
        try {
            char value = (char) Integer.parseInt(input.substring(index, index + 4), 16);
            index += 4;
            return value;
        } catch (NumberFormatException exception) {
            throw error("Invalid Unicode escape");
        }
    }

    private Object number() {
        int start = index;
        if (consume('-') && index >= input.length()) throw error("Invalid JSON number");
        if (consume('0')) {
            // Leading zero is complete unless a fraction or exponent follows.
        } else {
            digits();
        }
        if (consume('.')) digits();
        if (index < input.length() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
            index++;
            if (index < input.length() && (input.charAt(index) == '+' || input.charAt(index) == '-')) index++;
            digits();
        }
        String raw = input.substring(start, index);
        try {
            BigDecimal value = new BigDecimal(raw);
            try {
                return value.intValueExact();
            } catch (ArithmeticException ignored) {
                try {
                    return value.longValueExact();
                } catch (ArithmeticException ignoredAgain) {
                    return value;
                }
            }
        } catch (NumberFormatException exception) {
            throw error("Invalid JSON number");
        }
    }

    private void digits() {
        int start = index;
        while (index < input.length() && Character.isDigit(input.charAt(index))) index++;
        if (start == index) throw error("Invalid JSON number");
    }

    private Object literal(String expected, Object value) {
        if (!input.startsWith(expected, index)) throw error("Invalid JSON literal");
        index += expected.length();
        return value;
    }

    private void whitespace() {
        while (index < input.length() && Character.isWhitespace(input.charAt(index))) index++;
    }

    private boolean consume(char expected) {
        if (index < input.length() && input.charAt(index) == expected) {
            index++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!consume(expected)) throw error("Expected '" + expected + "'");
    }

    private ApiException error(String message) {
        return ApiException.badRequest("invalid_json", message + " at character " + index);
    }
}
