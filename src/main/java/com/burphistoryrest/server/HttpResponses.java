/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.server;

import com.burphistoryrest.BuildInfo;
import com.burphistoryrest.server.http.HttpExchange;
import com.burphistoryrest.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HttpResponses {
    private HttpResponses() {
    }

    public static void json(HttpExchange exchange, int status, Object value) throws IOException {
        bytes(exchange, status, "application/json; charset=UTF-8", Json.toBytes(value), Map.of());
    }

    public static void json(HttpExchange exchange, int status, Object value, Map<String, String> headers) throws IOException {
        bytes(exchange, status, "application/json; charset=UTF-8", Json.toBytes(value), headers);
    }

    public static void ndjson(HttpExchange exchange, int status, byte[] value, Map<String, String> headers) throws IOException {
        bytes(exchange, status, "application/x-ndjson; charset=UTF-8", value, headers);
    }

    public static void text(HttpExchange exchange, int status, String value) throws IOException {
        bytes(exchange, status, "text/plain; charset=UTF-8", value.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    public static void html(HttpExchange exchange, int status, byte[] value) throws IOException {
        bytes(exchange, status, "text/html; charset=UTF-8", value, Map.of(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                        + "connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
                "Referrer-Policy", "no-referrer"
        ));
    }

    public static void bytes(
            HttpExchange exchange,
            int status,
            String contentType,
            byte[] body,
            Map<String, String> additionalHeaders
    ) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cache-Control", "no-store");
        headers.put("Pragma", "no-cache");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("X-API-Version", BuildInfo.API_VERSION);
        headers.putAll(additionalHeaders);
        exchange.send(status, contentType, body, headers);
    }

    public static void redirect(HttpExchange exchange, String location) throws IOException {
        bytes(exchange, 302, "text/plain; charset=UTF-8", new byte[0], Map.of("Location", location));
    }

    public static void methodNotAllowed(HttpExchange exchange) throws IOException {
        methodNotAllowed(exchange, java.util.Set.of("GET", "HEAD"));
    }

    public static void methodNotAllowed(HttpExchange exchange, java.util.Set<String> allowedMethods) throws IOException {
        String allowed = allowedMethods.stream().sorted().collect(java.util.stream.Collectors.joining(", "));
        json(exchange, 405, Map.of("error", Map.of(
                "code", "method_not_allowed",
                "message", "Allowed method(s): " + allowed
        )), Map.of("Allow", allowed));
    }

    public static void forbidden(HttpExchange exchange, String requiredScope) throws IOException {
        json(exchange, 403, Map.of("error", Map.of(
                "code", "insufficient_scope",
                "message", "This endpoint requires scope: " + requiredScope
        )));
    }

    public static void unauthorized(HttpExchange exchange) throws IOException {
        json(exchange, 401, Map.of("error", Map.of(
                "code", "unauthorized",
                "message", "Supply Authorization: Bearer <token> or X-API-Key: <token>"
        )), Map.of("WWW-Authenticate", "Bearer realm=\"Burp History REST API\""));
    }
}
