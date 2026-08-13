/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.server;

import java.util.LinkedHashMap;
import java.util.Map;

@SuppressWarnings("serial")
public final class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, Object> details;
    private final Map<String, String> headers;

    public ApiException(int status, String code, String message) {
        this(status, code, message, Map.of(), Map.of());
    }

    public ApiException(int status, String code, String message, Map<String, Object> details) {
        this(status, code, message, details, Map.of());
    }

    public ApiException(
            int status,
            String code,
            String message,
            Map<String, Object> details,
            Map<String, String> headers
    ) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = Map.copyOf(details);
        this.headers = Map.copyOf(headers);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Map<String, Object> responseBody() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", getMessage());
        if (!details.isEmpty()) {
            error.put("details", details);
        }
        return Map.of("error", error);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException conflict(String code, String message, Map<String, Object> details) {
        return new ApiException(409, code, message, details);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }

    public static ApiException tooLarge(String code, String message, long length, long maximum) {
        return new ApiException(413, code, message, Map.of("length", length, "maximum", maximum));
    }

    public static ApiException scanLimit(int available, int maximum) {
        return new ApiException(
                422,
                "history_scan_limit",
                "Proxy history exceeds the configured scan safety limit",
                Map.of("availableItems", available, "maximumScannedItems", maximum)
        );
    }

    public static ApiException queryTimeout(int timeoutMs, int scannedItems) {
        return new ApiException(
                503,
                "history_query_timeout",
                "The history query exceeded its configured execution timeout",
                Map.of("timeoutMs", timeoutMs, "scannedItems", scannedItems),
                Map.of("Retry-After", "1")
        );
    }

    public static ApiException busy() {
        return new ApiException(
                429,
                "history_busy",
                "Too many history queries are already running",
                Map.of(),
                Map.of("Retry-After", "1")
        );
    }
}
