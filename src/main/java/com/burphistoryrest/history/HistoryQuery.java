/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.history;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public record HistoryQuery(
        List<Pattern> hostPatterns,
        List<String> hostInputs,
        Set<String> methods,
        List<String> pathTerms,
        List<String> urlTerms,
        List<String> queryTerms,
        List<NameValueFilter> queryParameterFilters,
        Integer servicePort,
        Boolean secure,
        StatusMatcher statusMatcher,
        List<String> statusInputs,
        Set<String> mimeTypes,
        Set<String> extensions,
        List<String> reasonTerms,
        Integer listenerPort,
        Boolean hasResponse,
        Boolean edited,
        Boolean inScope,
        Set<Integer> ids,
        Integer idFrom,
        Integer idTo,
        Integer afterId,
        Integer beforeId,
        SizeRange requestLength,
        SizeRange responseLength,
        StructuredFilters structuredFilters,
        List<String> keywords,
        KeywordMode keywordMode,
        SearchLocation searchLocation,
        Pattern searchPattern,
        boolean caseSensitive,
        Instant from,
        Instant to,
        String timezone,
        SortField sortField,
        SortOrder sortOrder,
        Set<IncludeField> includeFields,
        OutputFormat outputFormat,
        int offset,
        int limit
) {
    public HistoryQuery {
        hostPatterns = List.copyOf(hostPatterns);
        hostInputs = List.copyOf(hostInputs);
        methods = Set.copyOf(methods);
        pathTerms = List.copyOf(pathTerms);
        urlTerms = List.copyOf(urlTerms);
        queryTerms = List.copyOf(queryTerms);
        queryParameterFilters = List.copyOf(queryParameterFilters);
        statusInputs = List.copyOf(statusInputs);
        mimeTypes = Set.copyOf(mimeTypes);
        extensions = Set.copyOf(extensions);
        reasonTerms = List.copyOf(reasonTerms);
        ids = Set.copyOf(ids);
        keywords = List.copyOf(keywords);
        includeFields = Set.copyOf(includeFields);
    }

    public boolean matches(ProxyHttpRequestResponse item) {
        HttpRequest request = safe(item::finalRequest);
        if (request == null) {
            return false;
        }

        HttpService service = safe(request::httpService);
        if (!hostPatterns.isEmpty()) {
            String host = service == null ? null : safe(service::host);
            if (host == null || hostPatterns.stream().noneMatch(pattern -> pattern.matcher(host).matches())) {
                return false;
            }
        }

        if (!methods.isEmpty()) {
            String method = safe(request::method);
            if (method == null || !methods.contains(method.toUpperCase(Locale.ROOT))) {
                return false;
            }
        }

        String requestPath = safe(request::path);
        String requestPathWithoutQuery = safe(request::pathWithoutQuery);
        if (!pathTerms.isEmpty()
                && !containsAny(requestPath, pathTerms, caseSensitive)
                && !containsAny(requestPathWithoutQuery, pathTerms, caseSensitive)) {
            return false;
        }

        String requestUrl = safe(request::url);
        if (!urlTerms.isEmpty() && (requestUrl == null || !containsAny(requestUrl, urlTerms, caseSensitive))) {
            return false;
        }

        String requestQuery = safe(request::query);
        if (!queryTerms.isEmpty() && (requestQuery == null || !containsAny(requestQuery, queryTerms, caseSensitive))) {
            return false;
        }
        if (!queryParameterFilters.isEmpty()
                && !matchesAny(queryParameterFilters, HttpMessageMetadata.queryParameters(request), caseSensitive, false)) {
            return false;
        }

        if (servicePort != null) {
            Integer port = service == null ? null : safe(service::port);
            if (!servicePort.equals(port)) {
                return false;
            }
        }

        if (secure != null) {
            Boolean itemSecure = service == null ? null : safe(service::secure);
            if (!secure.equals(itemSecure)) {
                return false;
            }
        }

        int itemId = safeInt(item::id, -1);
        if (!ids.isEmpty() && !ids.contains(itemId)) {
            return false;
        }
        if (idFrom != null && itemId < idFrom) {
            return false;
        }
        if (idTo != null && itemId > idTo) {
            return false;
        }
        if (afterId != null && itemId <= afterId) {
            return false;
        }
        if (beforeId != null && itemId >= beforeId) {
            return false;
        }

        boolean itemHasResponse = safeBoolean(item::hasResponse);
        if (hasResponse != null && hasResponse != itemHasResponse) {
            return false;
        }

        Integer status = null;
        HttpResponse response = null;
        if (itemHasResponse) {
            response = safe(item::response);
            if (response != null) {
                Short code = safe(response::statusCode);
                status = code == null ? null : (int) code;
            }
        }
        if (!statusMatcher.matches(status)) {
            return false;
        }

        if (!reasonTerms.isEmpty()) {
            String reason = response == null ? null : safe(response::reasonPhrase);
            if (reason == null || !containsAny(reason, reasonTerms, caseSensitive)) {
                return false;
            }
        }

        if (!mimeTypes.isEmpty()) {
            MimeType mimeType = safe(item::mimeType);
            if (mimeType == null) {
                return false;
            }
            String name = mimeType.name().toLowerCase(Locale.ROOT);
            String description = mimeType.description().toLowerCase(Locale.ROOT);
            if (mimeTypes.stream().noneMatch(value -> value.equals(name) || value.equals(description))) {
                return false;
            }
        }

        if (!extensions.isEmpty()) {
            String extension = safe(request::fileExtension);
            String normalized = extension == null ? "" : extension.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
            if (!extensions.contains(normalized)) {
                return false;
            }
        }

        if (listenerPort != null && listenerPort != safeInt(item::listenerPort, -1)) {
            return false;
        }
        if (edited != null && edited != safeBoolean(item::edited)) {
            return false;
        }
        if (inScope != null && inScope != safeBoolean(request::isInScope)) {
            return false;
        }

        int requestBytes = safeInt(() -> request.toByteArray().length(), -1);
        if (!requestLength.matches(requestBytes)) {
            return false;
        }
        HttpResponse lengthResponse = response;
        int responseBytes = lengthResponse == null ? -1 : safeInt(() -> lengthResponse.toByteArray().length(), -1);
        if (!responseLength.matches(responseBytes)) {
            return false;
        }

        Instant itemTime = safe(() -> item.time().toInstant());
        if (from != null && (itemTime == null || itemTime.isBefore(from))) {
            return false;
        }
        if (to != null && (itemTime == null || itemTime.isAfter(to))) {
            return false;
        }

        HttpMessageMetadata requestMetadata = structuredFilters.needsRequestMetadata() || searchLocation.needsStructuredData()
                ? HttpMessageMetadata.parse(request)
                : null;
        HttpMessageMetadata responseMetadata = response != null
                && (structuredFilters.needsResponseMetadata() || searchLocation.needsStructuredData())
                ? HttpMessageMetadata.parse(response)
                : null;

        if (!structuredFilters.matches(requestMetadata, responseMetadata, caseSensitive)) {
            return false;
        }

        if (searchPattern != null) {
            return regexMatches(item, request, response, requestMetadata, responseMetadata);
        }
        if (!keywords.isEmpty()) {
            HttpResponse finalResponse = response;
            HttpMessageMetadata finalRequestMetadata = requestMetadata;
            HttpMessageMetadata finalResponseMetadata = responseMetadata;
            return keywordMode == KeywordMode.ALL
                    ? keywords.stream().allMatch(keyword -> keywordMatches(
                            item, request, finalResponse, finalRequestMetadata, finalResponseMetadata, keyword
                    ))
                    : keywords.stream().anyMatch(keyword -> keywordMatches(
                            item, request, finalResponse, finalRequestMetadata, finalResponseMetadata, keyword
                    ));
        }
        return true;
    }

    public Comparator<ProxyHttpRequestResponse> comparator() {
        Comparator<ProxyHttpRequestResponse> primary = switch (sortField) {
            case TIME -> Comparator.comparing(
                    item -> safe(() -> item.time().toInstant()),
                    Comparator.nullsFirst(Comparator.naturalOrder())
            );
            case ID -> Comparator.comparingInt(ProxyHttpRequestResponse::id);
            case HOST -> Comparator.comparing(
                    item -> safeLower(() -> item.finalRequest().httpService().host()),
                    Comparator.naturalOrder()
            );
            case METHOD -> Comparator.comparing(
                    item -> safeUpper(() -> item.finalRequest().method()),
                    Comparator.naturalOrder()
            );
            case URL -> Comparator.comparing(
                    item -> safeString(() -> item.finalRequest().url()),
                    Comparator.naturalOrder()
            );
            case PATH -> Comparator.comparing(
                    item -> safeString(() -> item.finalRequest().path()),
                    Comparator.naturalOrder()
            );
            case PORT -> Comparator.comparingInt(item -> safeInt(() -> item.finalRequest().httpService().port(), -1));
            case STATUS -> Comparator.comparingInt(HistoryQuery::statusCode);
            case MIME -> Comparator.comparing(
                    item -> safeString(() -> item.mimeType().name()).toLowerCase(Locale.ROOT),
                    Comparator.naturalOrder()
            );
            case LISTENER_PORT -> Comparator.comparingInt(item -> safeInt(item::listenerPort, -1));
            case REQUEST_LENGTH -> Comparator.comparingInt(item -> {
                HttpRequest request = safe(item::finalRequest);
                return request == null ? -1 : safeInt(() -> request.toByteArray().length(), -1);
            });
            case RESPONSE_LENGTH -> Comparator.comparingInt(item -> {
                if (!safeBoolean(item::hasResponse)) {
                    return -1;
                }
                HttpResponse response = safe(item::response);
                return response == null ? -1 : safeInt(() -> response.toByteArray().length(), -1);
            });
        };
        Comparator<ProxyHttpRequestResponse> stable = sortField == SortField.ID
                ? primary
                : primary.thenComparingInt(ProxyHttpRequestResponse::id);
        return sortOrder == SortOrder.DESC ? stable.reversed() : stable;
    }

    public Map<String, Object> describe() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("host", hostInputs);
        result.put("method", methods);
        result.put("path", pathTerms);
        result.put("url", urlTerms);
        result.put("query", queryTerms);
        result.put("queryParameter", queryParameterFilters.stream().map(NameValueFilter::describe).toList());
        result.put("port", servicePort);
        result.put("secure", secure);
        result.put("status", statusInputs);
        result.put("mime", mimeTypes);
        result.put("extension", extensions);
        result.put("reason", reasonTerms);
        result.put("listenerPort", listenerPort);
        result.put("hasResponse", hasResponse);
        result.put("edited", edited);
        result.put("inScope", inScope);
        result.put("id", ids);
        result.put("idFrom", idFrom);
        result.put("idTo", idTo);
        result.put("afterId", afterId);
        result.put("beforeId", beforeId);
        result.put("requestLength", requestLength.describe());
        result.put("responseLength", responseLength.describe());
        result.putAll(structuredFilters.describe());
        result.put("keywords", keywords);
        result.put("keywordMode", keywordMode.apiName());
        result.put("searchIn", searchLocation.apiName());
        result.put("regex", searchPattern != null);
        result.put("caseSensitive", caseSensitive);
        result.put("from", from == null ? null : from.toString());
        result.put("to", to == null ? null : to.toString());
        result.put("timezone", timezone);
        result.put("sort", sortField.apiName());
        result.put("order", sortOrder.name().toLowerCase(Locale.ROOT));
        result.put("include", includeFields.stream().map(IncludeField::apiName).toList());
        result.put("format", outputFormat.apiName());
        result.put("offset", offset);
        result.put("limit", limit);
        result.put("page", pageNumber());
        return result;
    }

    public int pageNumber() {
        return offset / limit + 1;
    }

    private boolean regexMatches(
            ProxyHttpRequestResponse item,
            HttpRequest request,
            HttpResponse response,
            HttpMessageMetadata requestMetadata,
            HttpMessageMetadata responseMetadata
    ) {
        return switch (searchLocation) {
            case ANY -> safeBoolean(() -> item.contains(searchPattern)) || metadataContains(item, request, response, searchPattern);
            case BOTH -> safeBoolean(() -> item.contains(searchPattern));
            case REQUEST -> safeBoolean(() -> request.contains(searchPattern));
            case RESPONSE -> response != null && safeBoolean(() -> response.contains(searchPattern));
            case METADATA -> metadataContains(item, request, response, searchPattern);
            case URL -> searchPattern.matcher(safeString(request::url)).find();
            case HEADERS -> searchPattern.matcher(headersText(requestMetadata, responseMetadata)).find();
            case COOKIES -> searchPattern.matcher(cookiesText(requestMetadata, responseMetadata)).find();
            case BODY -> searchPattern.matcher(bodyText(requestMetadata, responseMetadata)).find();
            case QUERY -> searchPattern.matcher(safeString(request::query)).find();
        };
    }

    private boolean keywordMatches(
            ProxyHttpRequestResponse item,
            HttpRequest request,
            HttpResponse response,
            HttpMessageMetadata requestMetadata,
            HttpMessageMetadata responseMetadata,
            String keyword
    ) {
        return switch (searchLocation) {
            case ANY -> safeBoolean(() -> item.contains(keyword, caseSensitive))
                    || metadataContains(item, request, response, keyword, caseSensitive);
            case BOTH -> safeBoolean(() -> item.contains(keyword, caseSensitive));
            case REQUEST -> safeBoolean(() -> request.contains(keyword, caseSensitive));
            case RESPONSE -> response != null && safeBoolean(() -> response.contains(keyword, caseSensitive));
            case METADATA -> metadataContains(item, request, response, keyword, caseSensitive);
            case URL -> contains(safeString(request::url), keyword, caseSensitive);
            case HEADERS -> contains(headersText(requestMetadata, responseMetadata), keyword, caseSensitive);
            case COOKIES -> contains(cookiesText(requestMetadata, responseMetadata), keyword, caseSensitive);
            case BODY -> contains(bodyText(requestMetadata, responseMetadata), keyword, caseSensitive);
            case QUERY -> contains(safeString(request::query), keyword, caseSensitive);
        };
    }

    private static String headersText(HttpMessageMetadata request, HttpMessageMetadata response) {
        StringBuilder value = new StringBuilder();
        appendHeaders(value, request);
        appendHeaders(value, response);
        return value.toString();
    }

    private static void appendHeaders(StringBuilder value, HttpMessageMetadata metadata) {
        if (metadata == null) {
            return;
        }
        metadata.headers().forEach((name, values) -> values.forEach(headerValue -> value
                .append(name).append(": ").append(headerValue).append('\n')));
    }

    private static String cookiesText(HttpMessageMetadata request, HttpMessageMetadata response) {
        StringBuilder value = new StringBuilder();
        if (request != null) {
            request.requestCookies().forEach(pair -> value.append(pair.name()).append('=').append(pair.value()).append('\n'));
        }
        if (response != null) {
            response.responseCookies().forEach(pair -> value.append(pair.name()).append('=').append(pair.value()).append('\n'));
        }
        return value.toString();
    }

    private static String bodyText(HttpMessageMetadata request, HttpMessageMetadata response) {
        return (request == null ? "" : request.bodyText()) + '\n' + (response == null ? "" : response.bodyText());
    }

    private static boolean metadataContains(
            ProxyHttpRequestResponse item,
            HttpRequest request,
            HttpResponse response,
            String term,
            boolean caseSensitive
    ) {
        return contains(metadata(item, request, response), term, caseSensitive);
    }

    private static boolean metadataContains(
            ProxyHttpRequestResponse item,
            HttpRequest request,
            HttpResponse response,
            Pattern pattern
    ) {
        return pattern.matcher(metadata(item, request, response)).find();
    }

    private static String metadata(ProxyHttpRequestResponse item, HttpRequest request, HttpResponse response) {
        StringBuilder value = new StringBuilder(256);
        append(value, safe(request::method));
        append(value, safe(request::url));
        append(value, safe(request::path));
        append(value, safe(request::query));
        HttpService service = safe(request::httpService);
        if (service != null) {
            append(value, safe(service::host));
            append(value, Integer.toString(safeInt(service::port, -1)));
        }
        append(value, Integer.toString(safeInt(item::id, -1)));
        append(value, safe(() -> item.time().toString()));
        append(value, safe(() -> item.mimeType().name()));
        if (response != null) {
            append(value, Integer.toString(safeInt(response::statusCode, -1)));
            append(value, safe(response::reasonPhrase));
        }
        return value.toString();
    }

    private static void append(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(value).append('\n');
        }
    }

    private static int statusCode(ProxyHttpRequestResponse item) {
        if (!safeBoolean(item::hasResponse)) {
            return -1;
        }
        HttpResponse response = safe(item::response);
        return response == null ? -1 : safeInt(response::statusCode, -1);
    }

    private static boolean matchesAny(
            List<NameValueFilter> filters,
            List<HttpMessageMetadata.NameValue> pairs,
            boolean caseSensitive,
            boolean namesAlwaysInsensitive
    ) {
        if (filters.isEmpty()) {
            return true;
        }
        return filters.stream().anyMatch(filter -> pairs.stream().anyMatch(pair -> {
            boolean nameMatches = namesAlwaysInsensitive
                    ? pair.name().equalsIgnoreCase(filter.name())
                    : equals(pair.name(), filter.name(), caseSensitive);
            return nameMatches && (!filter.hasValue() || contains(pair.value(), filter.value(), caseSensitive));
        }));
    }

    private static boolean matchesHeaders(
            List<NameValueFilter> filters,
            HttpMessageMetadata metadata,
            boolean caseSensitive
    ) {
        if (filters.isEmpty()) {
            return true;
        }
        if (metadata == null) {
            return false;
        }
        return filters.stream().anyMatch(filter -> metadata.headers().entrySet().stream().anyMatch(entry ->
                entry.getKey().equalsIgnoreCase(filter.name())
                        && (!filter.hasValue() || entry.getValue().stream()
                        .anyMatch(value -> contains(value, filter.value(), caseSensitive)))
        ));
    }

    private static boolean containsAny(String value, List<String> terms, boolean caseSensitive) {
        return terms.stream().anyMatch(term -> contains(value, term, caseSensitive));
    }

    private static boolean equals(String left, String right, boolean caseSensitive) {
        return caseSensitive ? left.equals(right) : left.equalsIgnoreCase(right);
    }

    private static boolean contains(String value, String term, boolean caseSensitive) {
        if (value == null || term == null) {
            return false;
        }
        if (caseSensitive) {
            return value.contains(term);
        }
        return value.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }

    private static String safeLower(Supplier<String> supplier) {
        return safeString(supplier).toLowerCase(Locale.ROOT);
    }

    private static String safeUpper(Supplier<String> supplier) {
        return safeString(supplier).toUpperCase(Locale.ROOT);
    }

    private static String safeString(Supplier<String> supplier) {
        String value = safe(supplier);
        return value == null ? "" : value;
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

    public record SizeRange(Integer minimum, Integer maximum) {
        public boolean matches(int value) {
            if (minimum == null && maximum == null) {
                return true;
            }
            if (value < 0) {
                return false;
            }
            return (minimum == null || value >= minimum) && (maximum == null || value <= maximum);
        }

        public Map<String, Integer> describe() {
            Map<String, Integer> result = new LinkedHashMap<>();
            result.put("minimum", minimum);
            result.put("maximum", maximum);
            return result;
        }
    }

    public record StructuredFilters(
            List<NameValueFilter> requestHeaders,
            List<NameValueFilter> responseHeaders,
            List<NameValueFilter> headers,
            List<NameValueFilter> requestCookies,
            List<NameValueFilter> responseCookies,
            List<NameValueFilter> cookies,
            List<String> requestBodyTerms,
            List<String> responseBodyTerms,
            List<String> bodyTerms
    ) {
        public StructuredFilters {
            requestHeaders = List.copyOf(requestHeaders);
            responseHeaders = List.copyOf(responseHeaders);
            headers = List.copyOf(headers);
            requestCookies = List.copyOf(requestCookies);
            responseCookies = List.copyOf(responseCookies);
            cookies = List.copyOf(cookies);
            requestBodyTerms = List.copyOf(requestBodyTerms);
            responseBodyTerms = List.copyOf(responseBodyTerms);
            bodyTerms = List.copyOf(bodyTerms);
        }

        public boolean needsRequestMetadata() {
            return !requestHeaders.isEmpty() || !headers.isEmpty() || !requestCookies.isEmpty() || !cookies.isEmpty()
                    || !requestBodyTerms.isEmpty() || !bodyTerms.isEmpty();
        }

        public boolean needsResponseMetadata() {
            return !responseHeaders.isEmpty() || !headers.isEmpty() || !responseCookies.isEmpty() || !cookies.isEmpty()
                    || !responseBodyTerms.isEmpty() || !bodyTerms.isEmpty();
        }

        public boolean matches(HttpMessageMetadata request, HttpMessageMetadata response, boolean caseSensitive) {
            if (!matchesHeaders(requestHeaders, request, caseSensitive)
                    || !matchesHeaders(responseHeaders, response, caseSensitive)) {
                return false;
            }
            if (!headers.isEmpty()
                    && !matchesHeaders(headers, request, caseSensitive)
                    && !matchesHeaders(headers, response, caseSensitive)) {
                return false;
            }

            List<HttpMessageMetadata.NameValue> requestCookiePairs = request == null
                    ? List.of() : request.requestCookies();
            List<HttpMessageMetadata.NameValue> responseCookiePairs = response == null
                    ? List.of() : response.responseCookies();
            if (!matchesAny(requestCookies, requestCookiePairs, caseSensitive, true)
                    || !matchesAny(responseCookies, responseCookiePairs, caseSensitive, true)) {
                return false;
            }
            if (!cookies.isEmpty()
                    && !matchesAny(cookies, requestCookiePairs, caseSensitive, true)
                    && !matchesAny(cookies, responseCookiePairs, caseSensitive, true)) {
                return false;
            }

            String requestBody = request == null ? "" : request.bodyText();
            String responseBody = response == null ? "" : response.bodyText();
            if (!requestBodyTerms.isEmpty() && !containsAny(requestBody, requestBodyTerms, caseSensitive)) {
                return false;
            }
            if (!responseBodyTerms.isEmpty() && !containsAny(responseBody, responseBodyTerms, caseSensitive)) {
                return false;
            }
            return bodyTerms.isEmpty()
                    || containsAny(requestBody, bodyTerms, caseSensitive)
                    || containsAny(responseBody, bodyTerms, caseSensitive);
        }

        public Map<String, Object> describe() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("requestHeader", requestHeaders.stream().map(NameValueFilter::describe).toList());
            result.put("responseHeader", responseHeaders.stream().map(NameValueFilter::describe).toList());
            result.put("header", headers.stream().map(NameValueFilter::describe).toList());
            result.put("requestCookie", requestCookies.stream().map(NameValueFilter::describe).toList());
            result.put("responseCookie", responseCookies.stream().map(NameValueFilter::describe).toList());
            result.put("cookie", cookies.stream().map(NameValueFilter::describe).toList());
            result.put("requestBody", requestBodyTerms);
            result.put("responseBody", responseBodyTerms);
            result.put("body", bodyTerms);
            return result;
        }
    }

    public enum KeywordMode {
        ANY("any"),
        ALL("all");

        private final String apiName;

        KeywordMode(String apiName) {
            this.apiName = apiName;
        }

        public String apiName() {
            return apiName;
        }
    }

    public enum SearchLocation {
        ANY("any", false),
        BOTH("both", false),
        REQUEST("request", false),
        RESPONSE("response", false),
        METADATA("metadata", false),
        URL("url", false),
        HEADERS("headers", true),
        COOKIES("cookies", true),
        BODY("body", true),
        QUERY("query", false);

        private final String apiName;
        private final boolean structuredData;

        SearchLocation(String apiName, boolean structuredData) {
            this.apiName = apiName;
            this.structuredData = structuredData;
        }

        public String apiName() {
            return apiName;
        }

        public boolean needsStructuredData() {
            return structuredData;
        }
    }

    public enum SortField {
        TIME("time"),
        ID("id"),
        HOST("host"),
        METHOD("method"),
        URL("url"),
        PATH("path"),
        PORT("port"),
        STATUS("status"),
        MIME("mime"),
        LISTENER_PORT("listener_port"),
        REQUEST_LENGTH("request_length"),
        RESPONSE_LENGTH("response_length");

        private final String apiName;

        SortField(String apiName) {
            this.apiName = apiName;
        }

        public String apiName() {
            return apiName;
        }
    }

    public enum SortOrder {
        ASC,
        DESC
    }

    public enum IncludeField {
        REQUEST_HEADERS("request_headers"),
        RESPONSE_HEADERS("response_headers"),
        REQUEST_COOKIES("request_cookies"),
        RESPONSE_COOKIES("response_cookies"),
        QUERY_PARAMETERS("query_parameters"),
        REQUEST_BODY_PREVIEW("request_body_preview"),
        RESPONSE_BODY_PREVIEW("response_body_preview");

        private final String apiName;

        IncludeField(String apiName) {
            this.apiName = apiName;
        }

        public String apiName() {
            return apiName;
        }
    }

    public enum OutputFormat {
        JSON("json"),
        NDJSON("ndjson");

        private final String apiName;

        OutputFormat(String apiName) {
            this.apiName = apiName;
        }

        public String apiName() {
            return apiName;
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
