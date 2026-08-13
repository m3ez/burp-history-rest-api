/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.util;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

/** Minimal dependency-free JSON writer for API responses. */
public final class Json {
    private Json() {
    }

    public static byte[] toBytes(Object value) {
        return stringify(value).getBytes(StandardCharsets.UTF_8);
    }

    public static String stringify(Object value) {
        StringBuilder builder = new StringBuilder(256);
        writeValue(builder, value);
        return builder.toString();
    }

    private static void writeValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String string) {
            writeString(builder, string);
        } else if (value instanceof Character character) {
            writeString(builder, character.toString());
        } else if (value instanceof Boolean) {
            builder.append(value);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger
                || value instanceof java.math.BigDecimal) {
            builder.append(value);
        } else if (value instanceof Float number) {
            writeFloatingPoint(builder, number.doubleValue());
        } else if (value instanceof Double number) {
            writeFloatingPoint(builder, number);
        } else if (value instanceof Enum<?> enumValue) {
            writeString(builder, enumValue.name());
        } else if (value instanceof Map<?, ?> map) {
            writeMap(builder, map);
        } else if (value instanceof Iterable<?> iterable) {
            writeIterable(builder, iterable);
        } else if (value.getClass().isArray()) {
            writeArray(builder, value);
        } else {
            writeString(builder, value.toString());
        }
    }

    private static void writeFloatingPoint(StringBuilder builder, double value) {
        if (!Double.isFinite(value)) {
            builder.append("null");
        } else {
            builder.append(value);
        }
    }

    private static void writeMap(StringBuilder builder, Map<?, ?> map) {
        builder.append('{');
        Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<?, ?> entry = iterator.next();
            writeString(builder, String.valueOf(entry.getKey()));
            builder.append(':');
            writeValue(builder, entry.getValue());
            if (iterator.hasNext()) {
                builder.append(',');
            }
        }
        builder.append('}');
    }

    private static void writeIterable(StringBuilder builder, Iterable<?> iterable) {
        builder.append('[');
        Iterator<?> iterator = iterable.iterator();
        while (iterator.hasNext()) {
            writeValue(builder, iterator.next());
            if (iterator.hasNext()) {
                builder.append(',');
            }
        }
        builder.append(']');
    }

    private static void writeArray(StringBuilder builder, Object array) {
        builder.append('[');
        int length = Array.getLength(array);
        for (int index = 0; index < length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            writeValue(builder, Array.get(array, index));
        }
        builder.append(']');
    }

    private static void writeString(StringBuilder builder, String value) {
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        builder.append('"');
    }
}
