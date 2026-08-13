/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.history;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpMessage;
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import com.burphistoryrest.server.ApiException;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public final class HistoryMapper {
    private static final int BODY_PREVIEW_CHARACTERS = 2_048;

    private HistoryMapper() {
    }

    public static Map<String, Object> summary(ProxyHttpRequestResponse item, RedactionPolicy redaction) {
        return summary(item, Set.of(), redaction);
    }

    public static Map<String, Object> summary(
            ProxyHttpRequestResponse item,
            Set<HistoryQuery.IncludeField> includeFields,
            RedactionPolicy redaction
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        HttpRequest request = safe(item::finalRequest);
        HttpService service = request == null ? safe(item::httpService) : safe(request::httpService);
        boolean hasResponse = safeBoolean(item::hasResponse);
        HttpResponse response = hasResponse ? safe(item::response) : null;
        ZonedDateTime time = safe(item::time);
        MimeType mimeType = safe(item::mimeType);
        HttpMessageMetadata requestMetadata = request == null ? null : HttpMessageMetadata.parse(request);
        HttpMessageMetadata responseMetadata = response == null ? null : HttpMessageMetadata.parse(response);
        List<HttpMessageMetadata.NameValue> queryParameters = HttpMessageMetadata.queryParameters(request);
        List<HttpMessageMetadata.NameValue> requestCookies = requestMetadata == null
                ? List.of() : requestMetadata.requestCookies();
        List<HttpMessageMetadata.NameValue> responseCookies = responseMetadata == null
                ? List.of() : responseMetadata.responseCookies();

        int id = safeInt(item::id, -1);
        result.put("id", id);
        result.put("time", time == null ? null : time.toString());
        result.put("datetime", time == null ? null : time.toString());
        result.put("timestamp", time == null ? null : time.toInstant().toString());
        result.put("timestampEpoch", time == null ? null : time.toInstant().getEpochSecond());
        result.put("timestampEpochMillis", time == null ? null : time.toInstant().toEpochMilli());
        result.put("timeEpochMillis", time == null ? null : time.toInstant().toEpochMilli());
        result.put("timezone", time == null ? null : time.getZone().getId());
        result.put("utcOffset", time == null ? null : time.getOffset().toString());
        result.put("method", request == null ? null : safe(request::method));
        result.put("url", request == null ? null : redaction.url(safe(request::url)));
        result.put("path", request == null ? null : redaction.path(safe(request::path)));
        result.put("pathWithoutQuery", request == null ? null : safe(request::pathWithoutQuery));
        result.put("query", request == null ? null : redaction.query(safe(request::query)));
        result.put("queryParameterCount", queryParameters.size());
        result.put("fileExtension", request == null ? null : safe(request::fileExtension));
        result.put("host", service == null ? null : safe(service::host));
        result.put("port", service == null ? null : safeInt(service::port, -1));
        Boolean secure = service == null ? null : safe(service::secure);
        result.put("secure", secure);
        result.put("protocol", secure == null ? null : secure ? "https" : "http");
        result.put("httpVersion", request == null ? null : safe(request::httpVersion));
        result.put("inScope", request == null ? null : safeBoolean(request::isInScope));
        result.put("status", response == null ? null : safeInt(response::statusCode, -1));
        result.put("reasonPhrase", response == null ? null : safe(response::reasonPhrase));
        result.put("responseHttpVersion", response == null ? null : safe(response::httpVersion));
        result.put("mimeType", mimeType == null ? null : mimeType.name());
        result.put("mimeDescription", mimeType == null ? null : mimeType.description());
        result.put("requestContentType", requestMetadata == null ? null : requestMetadata.firstHeader("Content-Type"));
        result.put("responseContentType", responseMetadata == null ? null : responseMetadata.firstHeader("Content-Type"));
        result.put("hasResponse", hasResponse);
        result.put("edited", safeBoolean(item::edited));
        result.put("listenerPort", safeInt(item::listenerPort, -1));
        result.put("requestLength", request == null ? null : safeInt(() -> request.toByteArray().length(), -1));
        result.put("responseLength", response == null ? null : safeInt(() -> response.toByteArray().length(), -1));
        result.put("requestBodyLength", requestMetadata == null ? null : requestMetadata.bodyLength());
        result.put("responseBodyLength", responseMetadata == null ? null : responseMetadata.bodyLength());
        result.put("requestHeaderCount", requestMetadata == null ? 0 : headerCount(requestMetadata));
        result.put("responseHeaderCount", responseMetadata == null ? 0 : headerCount(responseMetadata));
        result.put("requestCookieCount", requestCookies.size());
        result.put("responseCookieCount", responseCookies.size());
        result.put("redacted", redaction.enabled());

        if (includeFields.contains(HistoryQuery.IncludeField.REQUEST_HEADERS)) {
            result.put("requestHeaders", requestMetadata == null ? Map.of() : redaction.headers(requestMetadata.headers()));
        }
        if (includeFields.contains(HistoryQuery.IncludeField.RESPONSE_HEADERS)) {
            result.put("responseHeaders", responseMetadata == null ? Map.of() : redaction.headers(responseMetadata.headers()));
        }
        if (includeFields.contains(HistoryQuery.IncludeField.REQUEST_COOKIES)) {
            result.put("requestCookies", HttpMessageMetadata.pairsAsMaps(redaction.cookies(requestCookies)));
        }
        if (includeFields.contains(HistoryQuery.IncludeField.RESPONSE_COOKIES)) {
            result.put("responseCookies", HttpMessageMetadata.pairsAsMaps(redaction.cookies(responseCookies)));
        }
        if (includeFields.contains(HistoryQuery.IncludeField.QUERY_PARAMETERS)) {
            result.put("queryParameters", HttpMessageMetadata.pairsAsMaps(redaction.parameters(queryParameters)));
        }
        if (includeFields.contains(HistoryQuery.IncludeField.REQUEST_BODY_PREVIEW)) {
            result.put("requestBodyPreview", bodyPreview(requestMetadata, redaction));
        }
        if (includeFields.contains(HistoryQuery.IncludeField.RESPONSE_BODY_PREVIEW)) {
            result.put("responseBodyPreview", bodyPreview(responseMetadata, redaction));
        }

        result.put("links", Map.of(
                "self", "/api/v1/history/" + id,
                "request", "/api/v1/history/" + id + "/request",
                "response", "/api/v1/history/" + id + "/response"
        ));
        return result;
    }

    public static Map<String, Object> details(
            ProxyHttpRequestResponse item,
            MessageFormat format,
            int maximumMessageBytes,
            RedactionPolicy redaction
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("item", summary(item, redaction));
        result.put("redaction", redaction.describe());

        HttpRequest request = safe(item::finalRequest);
        if (request == null) {
            result.put("requestMetadata", null);
            result.put("request", null);
        } else {
            result.put("requestMetadata", structuredMetadata(request, true, redaction));
            result.put("request", envelope(request, format, maximumMessageBytes, redaction));
        }

        HttpResponse response = safeBoolean(item::hasResponse) ? safe(item::response) : null;
        if (response == null) {
            result.put("responseMetadata", null);
            result.put("response", null);
        } else {
            result.put("responseMetadata", structuredMetadata(response, false, redaction));
            result.put("response", envelope(response, format, maximumMessageBytes, redaction));
        }
        return result;
    }

    public static Map<String, Object> envelope(
            HttpMessage message,
            MessageFormat format,
            int maximumMessageBytes,
            RedactionPolicy redaction
    ) {
        ByteArray byteArray = message.toByteArray();
        int originalLength = byteArray.length();
        if (originalLength > maximumMessageBytes) {
            return Map.of(
                    "length", originalLength,
                    "omitted", true,
                    "maximum", maximumMessageBytes,
                    "message", "Message exceeds the configured maximum; use the extension tab to raise the limit"
            );
        }

        byte[] bytes = redaction.message(byteArray.getBytes());
        HttpMessageMetadata metadata = HttpMessageMetadata.parse(bytes);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("length", originalLength);
        result.put("outputLength", bytes.length);
        result.put("bodyLength", metadata.bodyLength());
        result.put("startLine", redaction.path(metadata.startLine()));
        result.put("contentType", metadata.firstHeader("Content-Type"));
        result.put("encoding", format.apiName());
        result.put("redacted", redaction.enabled());
        result.put("omitted", false);
        switch (format) {
            case BASE64 -> result.put("data", Base64.getEncoder().encodeToString(bytes));
            case TEXT -> {
                result.put("charset", "UTF-8");
                result.put("lossy", true);
                result.put("data", new String(bytes, StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    /** Exact unredacted bytes for explicitly requested raw endpoints. */
    public static byte[] exactBytes(HttpMessage message, int maximumMessageBytes) {
        ByteArray byteArray = message.toByteArray();
        if (byteArray.length() > maximumMessageBytes) {
            throw ApiException.tooLarge(
                    "message_too_large",
                    "The HTTP message exceeds the configured maximum size",
                    byteArray.length(),
                    maximumMessageBytes
            );
        }
        return byteArray.getBytes();
    }

    private static Map<String, Object> structuredMetadata(
            HttpMessage message,
            boolean request,
            RedactionPolicy redaction
    ) {
        HttpMessageMetadata metadata = HttpMessageMetadata.parse(message);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startLine", redaction.path(metadata.startLine()));
        result.put("headers", redaction.headers(metadata.headers()));
        result.put("headerCount", headerCount(metadata));
        result.put("contentType", metadata.firstHeader("Content-Type"));
        result.put("bodyLength", metadata.bodyLength());
        if (request) {
            result.put("cookies", HttpMessageMetadata.pairsAsMaps(redaction.cookies(metadata.requestCookies())));
        } else {
            result.put("cookies", HttpMessageMetadata.pairsAsMaps(redaction.cookies(metadata.responseCookies())));
        }
        return result;
    }

    private static Map<String, Object> bodyPreview(HttpMessageMetadata metadata, RedactionPolicy redaction) {
        if (metadata == null) {
            return Map.of("length", 0, "truncated", false, "data", "", "redacted", redaction.enabled());
        }
        String full = redaction.body(metadata.bodyText(), metadata.firstHeader("Content-Type"));
        String preview = full.length() <= BODY_PREVIEW_CHARACTERS ? full : full.substring(0, BODY_PREVIEW_CHARACTERS);
        return Map.of(
                "length", metadata.bodyLength(),
                "characters", full.length(),
                "truncated", preview.length() < full.length(),
                "charset", "UTF-8",
                "lossy", true,
                "redacted", redaction.enabled(),
                "data", preview
        );
    }

    private static int headerCount(HttpMessageMetadata metadata) {
        return metadata.headers().values().stream().mapToInt(List::size).sum();
    }

    public enum MessageFormat {
        BASE64("base64"),
        TEXT("text");

        private final String apiName;

        MessageFormat(String apiName) {
            this.apiName = apiName;
        }

        public String apiName() {
            return apiName;
        }

        public static MessageFormat parse(String value) {
            return switch (value.toLowerCase(java.util.Locale.ROOT)) {
                case "base64" -> BASE64;
                case "text" -> TEXT;
                default -> throw ApiException.badRequest("invalid_format", "format must be base64 or text");
            };
        }
    }

    private static boolean safeBoolean(BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static int safeInt(IntSupplier supplier, int fallback) {
        try {
            return supplier.getAsInt();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static <T> T safe(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }

    @FunctionalInterface
    private interface IntSupplier {
        int getAsInt();
    }
}
