/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.history;

import com.burphistoryrest.config.ApiSettings;
import com.burphistoryrest.server.ApiException;
import com.burphistoryrest.util.QueryParameters;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class HistoryQueryParser {
    private static final Set<String> ALLOWED_PARAMETERS = Set.of(
            "host", "domain", "method", "http_method", "path", "url_path", "url", "url_contains",
            "query_string", "query_contains", "query_param", "parameter", "param",
            "port", "service_port", "secure", "protocol", "status", "status_code", "reason", "reason_phrase",
            "mime", "content_type", "extension", "ext", "listener_port", "listener", "has_response", "response",
            "edited", "in_scope", "scope", "id", "history_id", "id_from", "id_to",
            "after_id", "since_id", "before_id", "cursor",
            "request_length_min", "request_size_min", "min_request_length",
            "request_length_max", "request_size_max", "max_request_length",
            "response_length_min", "response_size_min", "min_response_length",
            "response_length_max", "response_size_max", "max_response_length",
            "request_header", "req_header", "response_header", "resp_header", "header",
            "request_content_type", "response_content_type",
            "request_cookie", "req_cookie", "response_cookie", "resp_cookie", "set_cookie", "cookie",
            "request_body", "req_body", "response_body", "resp_body", "body",
            "q", "keyword", "keywords", "search", "query", "keyword_mode", "search_mode", "search_in",
            "regex", "case_sensitive", "from", "since", "start", "start_time", "start_datetime",
            "from_timestamp", "timestamp_from", "datetime_from", "to", "until", "end", "end_time",
            "end_datetime", "to_timestamp", "timestamp_to", "datetime_to", "date", "last", "timezone", "tz",
            "sort", "sort_by", "orderby", "order", "sort_order", "direction", "include", "expand",
            "offset", "page", "limit", "page_size", "per_page", "format"
    );
    private static final Pattern LOOKBACK = Pattern.compile("^(\\d+)(ms|s|m|h|d|w)$", Pattern.CASE_INSENSITIVE);

    private HistoryQueryParser() {
    }

    public static HistoryQuery parse(QueryParameters parameters, ApiSettings settings) {
        return parse(parameters, settings, null);
    }

    public static HistoryQuery parse(QueryParameters parameters, ApiSettings settings, String instanceId) {
        rejectUnknownParameters(parameters);

        List<String> hostInputs = splitCsv(values(parameters, "host", "domain"));
        List<Pattern> hosts = compileHostPatterns(hostInputs);
        Set<String> methods = uppercaseSet(splitCsv(values(parameters, "method", "http_method")));
        List<String> paths = nonBlank(values(parameters, "path", "url_path"));
        List<String> urls = nonBlank(values(parameters, "url", "url_contains"));
        List<String> queryTerms = nonBlank(values(parameters, "query_string", "query_contains"));
        List<NameValueFilter> queryParameters = NameValueFilter.parse(
                values(parameters, "query_param", "parameter", "param"), false, "query_param"
        );
        Integer servicePort = optionalInteger(parameters, 1, 65_535, "port", "service_port");

        Boolean secure = optionalBoolean(parameters, "secure");
        String protocol = singleAlias(parameters, "protocol");
        if (protocol != null) {
            Boolean protocolSecure = switch (protocol.trim().toLowerCase(Locale.ROOT)) {
                case "http" -> false;
                case "https" -> true;
                default -> throw ApiException.badRequest("invalid_protocol", "protocol must be http or https");
            };
            if (secure != null && !secure.equals(protocolSecure)) {
                throw ApiException.badRequest("conflicting_protocol", "secure and protocol filters conflict");
            }
            secure = protocolSecure;
        }

        List<String> statusInputs = values(parameters, "status", "status_code");
        StatusMatcher statusMatcher = StatusMatcher.parse(statusInputs);
        List<String> reasonTerms = nonBlank(values(parameters, "reason", "reason_phrase"));
        Set<String> mimeTypes = lowercaseSet(splitCsv(values(parameters, "mime", "content_type")));
        Set<String> extensions = normalizeExtensions(splitCsv(values(parameters, "extension", "ext")));
        Integer listenerPort = optionalInteger(parameters, 1, 65_535, "listener_port", "listener");
        Boolean hasResponse = optionalBoolean(parameters, "has_response", "response");
        Boolean edited = optionalBoolean(parameters, "edited");
        Boolean inScope = optionalBoolean(parameters, "in_scope", "scope");

        Set<Integer> ids = integerSet(splitCsv(values(parameters, "id", "history_id")), "id", 0, Integer.MAX_VALUE);
        Integer idFrom = optionalInteger(parameters, 0, Integer.MAX_VALUE, "id_from");
        Integer idTo = optionalInteger(parameters, 0, Integer.MAX_VALUE, "id_to");
        Integer afterId = optionalInteger(parameters, 0, Integer.MAX_VALUE, "after_id", "since_id");
        Integer beforeId = optionalInteger(parameters, 0, Integer.MAX_VALUE, "before_id");
        String cursorValue = singleAlias(parameters, "cursor");
        if (cursorValue != null) {
            if (afterId != null) {
                throw ApiException.badRequest("conflicting_cursor", "Use cursor or after_id, not both");
            }
            if (instanceId == null) {
                throw ApiException.badRequest("cursor_unavailable", "cursor requires a running API instance");
            }
            afterId = CursorCodec.decode(cursorValue, instanceId).afterId();
        }
        if (idFrom != null && idTo != null && idFrom > idTo) {
            throw ApiException.badRequest("invalid_id_range", "id_from must be less than or equal to id_to");
        }
        if (afterId != null && beforeId != null && afterId >= beforeId) {
            throw ApiException.badRequest("invalid_sync_range", "after_id must be less than before_id");
        }

        HistoryQuery.SizeRange requestLength = sizeRange(
                parameters,
                new String[]{"request_length_min", "request_size_min", "min_request_length"},
                new String[]{"request_length_max", "request_size_max", "max_request_length"},
                "request length"
        );
        HistoryQuery.SizeRange responseLength = sizeRange(
                parameters,
                new String[]{"response_length_min", "response_size_min", "min_response_length"},
                new String[]{"response_length_max", "response_size_max", "max_response_length"},
                "response length"
        );

        List<NameValueFilter> requestHeaders = new ArrayList<>(NameValueFilter.parse(
                values(parameters, "request_header", "req_header"), true, "request_header"
        ));
        for (String contentType : nonBlank(values(parameters, "request_content_type"))) {
            requestHeaders.add(new NameValueFilter("Content-Type", contentType));
        }
        List<NameValueFilter> responseHeaders = new ArrayList<>(NameValueFilter.parse(
                values(parameters, "response_header", "resp_header"), true, "response_header"
        ));
        for (String contentType : nonBlank(values(parameters, "response_content_type"))) {
            responseHeaders.add(new NameValueFilter("Content-Type", contentType));
        }
        List<NameValueFilter> headers = NameValueFilter.parse(values(parameters, "header"), true, "header");
        List<NameValueFilter> requestCookies = NameValueFilter.parse(
                values(parameters, "request_cookie", "req_cookie"), false, "request_cookie"
        );
        List<NameValueFilter> responseCookies = NameValueFilter.parse(
                values(parameters, "response_cookie", "resp_cookie", "set_cookie"), false, "response_cookie"
        );
        List<NameValueFilter> cookies = NameValueFilter.parse(values(parameters, "cookie"), false, "cookie");
        List<String> requestBodyTerms = nonBlank(values(parameters, "request_body", "req_body"));
        List<String> responseBodyTerms = nonBlank(values(parameters, "response_body", "resp_body"));
        List<String> bodyTerms = nonBlank(values(parameters, "body"));
        HistoryQuery.StructuredFilters structuredFilters = new HistoryQuery.StructuredFilters(
                requestHeaders,
                responseHeaders,
                headers,
                requestCookies,
                responseCookies,
                cookies,
                requestBodyTerms,
                responseBodyTerms,
                bodyTerms
        );

        List<String> keywords = parseKeywords(parameters);
        boolean caseSensitive = optionalBoolean(parameters, false, "case_sensitive");
        boolean regex = optionalBoolean(parameters, false, "regex");
        Pattern searchPattern = null;
        if (regex) {
            if (!settings.regexEnabled()) {
                throw ApiException.badRequest(
                        "regex_disabled",
                        "Regex search is disabled. Enable it in the Burp extension tab before using regex=true"
                );
            }
            if (keywords.size() != 1) {
                throw ApiException.badRequest("invalid_regex_search", "regex=true requires exactly one keyword");
            }
            String expression = keywords.getFirst();
            if (expression.length() > 512) {
                throw ApiException.badRequest("regex_too_long", "Regex patterns must not exceed 512 characters");
            }
            try {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                searchPattern = Pattern.compile(expression, flags);
            } catch (PatternSyntaxException exception) {
                throw ApiException.badRequest("invalid_regex", exception.getDescription());
            }
        }

        HistoryQuery.KeywordMode keywordMode = parseKeywordMode(
                firstAliasOrDefault(parameters, "any", "keyword_mode", "search_mode")
        );
        HistoryQuery.SearchLocation searchLocation = parseSearchLocation(
                firstAliasOrDefault(parameters, "any", "search_in")
        );

        String timezoneName = firstAliasOrDefault(parameters, "UTC", "timezone", "tz");
        ZoneId timezone = parseTimezone(timezoneName);
        TimeRange timeRange = parseTimeRange(parameters, timezone);

        boolean syncForward = afterId != null;
        boolean syncBackward = beforeId != null && afterId == null;
        String defaultSort = syncForward || syncBackward ? "id" : "time";
        String defaultOrder = syncForward ? "asc" : "desc";
        HistoryQuery.SortField sortField = parseSortField(
                firstAliasOrDefault(parameters, defaultSort, "sort", "sort_by", "orderby")
        );
        HistoryQuery.SortOrder sortOrder = parseSortOrder(
                firstAliasOrDefault(parameters, defaultOrder, "order", "sort_order", "direction")
        );
        if (syncForward && (sortField != HistoryQuery.SortField.ID || sortOrder != HistoryQuery.SortOrder.ASC)) {
            throw ApiException.badRequest(
                    "invalid_sync_sort", "cursor and after_id synchronization require sort=id&order=asc"
            );
        }
        Set<HistoryQuery.IncludeField> includeFields = parseIncludes(
                splitCsv(values(parameters, "include", "expand"))
        );
        HistoryQuery.OutputFormat outputFormat = parseOutputFormat(
                firstAliasOrDefault(parameters, "json", "format")
        );

        int limit = integerAlias(
                parameters,
                Math.min(100, settings.maxPageSize()),
                1,
                settings.maxPageSize(),
                "limit", "page_size", "per_page"
        );
        Integer page = optionalInteger(parameters, 1, 10_000_000, "page");
        Integer explicitOffset = optionalInteger(parameters, 0, 10_000_000, "offset");
        if (page != null && explicitOffset != null) {
            throw ApiException.badRequest("conflicting_pagination", "Use page or offset, not both");
        }
        int offset;
        try {
            offset = page == null ? (explicitOffset == null ? 0 : explicitOffset) : Math.multiplyExact(page - 1, limit);
        } catch (ArithmeticException exception) {
            throw ApiException.badRequest("invalid_page", "page and page_size produce an offset that is too large");
        }
        if (offset > 10_000_000) {
            throw ApiException.badRequest("invalid_offset", "Calculated offset must not exceed 10000000");
        }
        if (afterId != null && offset != 0) {
            throw ApiException.badRequest("invalid_sync_offset", "cursor and after_id synchronization require offset 0");
        }

        return new HistoryQuery(
                hosts,
                hostInputs,
                methods,
                paths,
                urls,
                queryTerms,
                queryParameters,
                servicePort,
                secure,
                statusMatcher,
                statusInputs,
                mimeTypes,
                extensions,
                reasonTerms,
                listenerPort,
                hasResponse,
                edited,
                inScope,
                ids,
                idFrom,
                idTo,
                afterId,
                beforeId,
                requestLength,
                responseLength,
                structuredFilters,
                keywords,
                keywordMode,
                searchLocation,
                searchPattern,
                caseSensitive,
                timeRange.from(),
                timeRange.to(),
                timezone.getId(),
                sortField,
                sortOrder,
                includeFields,
                outputFormat,
                offset,
                limit
        );
    }

    private static TimeRange parseTimeRange(QueryParameters parameters, ZoneId timezone) {
        String dateValue = singleAlias(parameters, "date");
        String lastValue = singleAlias(parameters, "last");
        String fromValue = singleAlias(
                parameters,
                "from", "since", "start", "start_time", "start_datetime", "from_timestamp", "timestamp_from", "datetime_from"
        );
        String toValue = singleAlias(
                parameters,
                "to", "until", "end", "end_time", "end_datetime", "to_timestamp", "timestamp_to", "datetime_to"
        );

        if (dateValue != null && (lastValue != null || fromValue != null || toValue != null)) {
            throw ApiException.badRequest("conflicting_time_filters", "date cannot be combined with from, to, or last");
        }
        if (lastValue != null && fromValue != null) {
            throw ApiException.badRequest("conflicting_time_filters", "last cannot be combined with a from/start filter");
        }

        Instant from = null;
        Instant to = null;
        if (dateValue != null) {
            try {
                LocalDate date = LocalDate.parse(dateValue.trim());
                from = date.atStartOfDay(timezone).toInstant();
                to = date.plusDays(1).atStartOfDay(timezone).toInstant().minusNanos(1);
            } catch (DateTimeParseException exception) {
                throw ApiException.badRequest("invalid_date", "date must use YYYY-MM-DD");
            }
        } else if (lastValue != null) {
            Duration lookback = parseLookback(lastValue);
            to = toValue == null ? Instant.now() : parseFlexibleInstant("to", toValue, timezone, Boundary.END);
            from = to.minus(lookback);
        } else {
            from = fromValue == null ? null : parseFlexibleInstant("from", fromValue, timezone, Boundary.START);
            to = toValue == null ? null : parseFlexibleInstant("to", toValue, timezone, Boundary.END);
        }

        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("invalid_time_range", "from must be before or equal to to");
        }
        return new TimeRange(from, to);
    }

    private static Instant parseFlexibleInstant(String parameter, String rawValue, ZoneId timezone, Boundary boundary) {
        String value = rawValue.trim();
        if (value.matches("^-?\\d{1,19}$")) {
            try {
                long numeric = Long.parseLong(value);
                return Math.abs(numeric) >= 100_000_000_000L
                        ? Instant.ofEpochMilli(numeric)
                        : Instant.ofEpochSecond(numeric);
            } catch (RuntimeException exception) {
                throw ApiException.badRequest("invalid_time", parameter + " contains an invalid Unix timestamp");
            }
        }

        try {
            return Instant.parse(value);
        } catch (DateTimeException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeException ignoredAgain) {
                try {
                    return ZonedDateTime.parse(value).toInstant();
                } catch (DateTimeException ignoredThird) {
                    try {
                        String normalized = value.indexOf(' ') >= 0 ? value.replace(' ', 'T') : value;
                        return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                .atZone(timezone)
                                .toInstant();
                    } catch (DateTimeException ignoredFourth) {
                        try {
                            LocalDate date = LocalDate.parse(value);
                            return boundary == Boundary.START
                                    ? date.atStartOfDay(timezone).toInstant()
                                    : date.plusDays(1).atStartOfDay(timezone).toInstant().minusNanos(1);
                        } catch (DateTimeException exception) {
                            throw ApiException.badRequest(
                                    "invalid_time",
                                    parameter + " must be ISO-8601, YYYY-MM-DD, a local datetime with timezone=, "
                                            + "or Unix seconds/milliseconds"
                            );
                        }
                    }
                }
            }
        }
    }

    private static Duration parseLookback(String value) {
        Matcher matcher = LOOKBACK.matcher(value.trim());
        if (!matcher.matches()) {
            throw ApiException.badRequest("invalid_last", "last must look like 30s, 15m, 2h, 7d, or 2w");
        }
        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw ApiException.badRequest("invalid_last", "last is too large");
        }
        if (amount <= 0) {
            throw ApiException.badRequest("invalid_last", "last must be greater than zero");
        }
        Duration duration = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            case "w" -> Duration.ofDays(Math.multiplyExact(amount, 7));
            default -> throw new IllegalStateException("Unexpected lookback unit");
        };
        if (duration.compareTo(Duration.ofDays(3_650)) > 0) {
            throw ApiException.badRequest("invalid_last", "last must not exceed 3650 days");
        }
        return duration;
    }

    private static ZoneId parseTimezone(String value) {
        try {
            return ZoneId.of(value.trim());
        } catch (DateTimeException exception) {
            throw ApiException.badRequest("invalid_timezone", "timezone must be an IANA zone such as Asia/Singapore or UTC");
        }
    }

    private static List<String> parseKeywords(QueryParameters parameters) {
        List<String> raw = new ArrayList<>();
        raw.addAll(values(parameters, "q", "keyword", "search", "query"));
        raw.addAll(splitCsv(values(parameters, "keywords")));

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        int totalLength = 0;
        for (String value : raw) {
            String keyword = value.trim();
            if (keyword.isEmpty()) {
                continue;
            }
            if (keyword.length() > 2_048) {
                throw ApiException.badRequest("search_too_long", "Each keyword must not exceed 2048 characters");
            }
            unique.add(keyword);
            totalLength += keyword.length();
        }
        if (unique.size() > 32 || totalLength > 8_192) {
            throw ApiException.badRequest("too_many_keywords", "Use at most 32 keywords with 8192 total characters");
        }
        return List.copyOf(unique);
    }

    private static void rejectUnknownParameters(QueryParameters parameters) {
        Set<String> unknown = new HashSet<>(parameters.asMap().keySet());
        unknown.removeAll(ALLOWED_PARAMETERS);
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("unknown_parameter", "Unknown query parameter(s): " + String.join(", ", unknown));
        }
    }

    private static List<Pattern> compileHostPatterns(List<String> values) {
        List<Pattern> result = new ArrayList<>();
        for (String value : values) {
            if (value.length() > 253) {
                throw ApiException.badRequest("invalid_host", "Host filters must not exceed 253 characters");
            }
            StringBuilder regex = new StringBuilder("^");
            for (char character : value.toCharArray()) {
                switch (character) {
                    case '*' -> regex.append(".*");
                    case '?' -> regex.append('.');
                    case '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' -> regex.append('\\').append(character);
                    default -> regex.append(character);
                }
            }
            regex.append('$');
            result.add(Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        return result;
    }

    private static HistoryQuery.KeywordMode parseKeywordMode(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "any", "or" -> HistoryQuery.KeywordMode.ANY;
            case "all", "and" -> HistoryQuery.KeywordMode.ALL;
            default -> throw ApiException.badRequest("invalid_keyword_mode", "keyword_mode must be any or all");
        };
    }

    private static HistoryQuery.SearchLocation parseSearchLocation(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "any", "everything" -> HistoryQuery.SearchLocation.ANY;
            case "both", "messages", "all" -> HistoryQuery.SearchLocation.BOTH;
            case "request", "req" -> HistoryQuery.SearchLocation.REQUEST;
            case "response", "resp" -> HistoryQuery.SearchLocation.RESPONSE;
            case "metadata", "meta" -> HistoryQuery.SearchLocation.METADATA;
            case "url" -> HistoryQuery.SearchLocation.URL;
            case "header", "headers" -> HistoryQuery.SearchLocation.HEADERS;
            case "cookie", "cookies" -> HistoryQuery.SearchLocation.COOKIES;
            case "body", "bodies" -> HistoryQuery.SearchLocation.BODY;
            case "query", "query_string", "parameters" -> HistoryQuery.SearchLocation.QUERY;
            default -> throw ApiException.badRequest(
                    "invalid_search_in",
                    "search_in must be any, both, request, response, metadata, url, headers, cookies, body, or query"
            );
        };
    }

    private static HistoryQuery.SortField parseSortField(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "time", "timestamp", "datetime", "date" -> HistoryQuery.SortField.TIME;
            case "id" -> HistoryQuery.SortField.ID;
            case "host", "domain" -> HistoryQuery.SortField.HOST;
            case "method", "http_method" -> HistoryQuery.SortField.METHOD;
            case "url" -> HistoryQuery.SortField.URL;
            case "path", "url_path" -> HistoryQuery.SortField.PATH;
            case "port", "service_port" -> HistoryQuery.SortField.PORT;
            case "status", "status_code" -> HistoryQuery.SortField.STATUS;
            case "mime", "content_type" -> HistoryQuery.SortField.MIME;
            case "listener", "listener_port" -> HistoryQuery.SortField.LISTENER_PORT;
            case "request_length", "requestlength", "request_size" -> HistoryQuery.SortField.REQUEST_LENGTH;
            case "response_length", "responselength", "response_size" -> HistoryQuery.SortField.RESPONSE_LENGTH;
            default -> throw ApiException.badRequest(
                    "invalid_sort",
                    "sort must be time, id, host, method, url, path, port, status, mime, listener_port, "
                            + "request_length, or response_length"
            );
        };
    }

    private static HistoryQuery.SortOrder parseSortOrder(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "asc", "ascending", "oldest" -> HistoryQuery.SortOrder.ASC;
            case "desc", "descending", "newest" -> HistoryQuery.SortOrder.DESC;
            default -> throw ApiException.badRequest("invalid_order", "order must be asc or desc");
        };
    }

    private static Set<HistoryQuery.IncludeField> parseIncludes(List<String> values) {
        LinkedHashSet<HistoryQuery.IncludeField> result = new LinkedHashSet<>();
        for (String raw : values) {
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "headers" -> {
                    result.add(HistoryQuery.IncludeField.REQUEST_HEADERS);
                    result.add(HistoryQuery.IncludeField.RESPONSE_HEADERS);
                }
                case "request_headers", "req_headers" -> result.add(HistoryQuery.IncludeField.REQUEST_HEADERS);
                case "response_headers", "resp_headers" -> result.add(HistoryQuery.IncludeField.RESPONSE_HEADERS);
                case "cookies" -> {
                    result.add(HistoryQuery.IncludeField.REQUEST_COOKIES);
                    result.add(HistoryQuery.IncludeField.RESPONSE_COOKIES);
                }
                case "request_cookies", "req_cookies" -> result.add(HistoryQuery.IncludeField.REQUEST_COOKIES);
                case "response_cookies", "resp_cookies", "set_cookies" -> result.add(HistoryQuery.IncludeField.RESPONSE_COOKIES);
                case "query_parameters", "parameters", "params" -> result.add(HistoryQuery.IncludeField.QUERY_PARAMETERS);
                case "body_preview", "bodies" -> {
                    result.add(HistoryQuery.IncludeField.REQUEST_BODY_PREVIEW);
                    result.add(HistoryQuery.IncludeField.RESPONSE_BODY_PREVIEW);
                }
                case "request_body_preview", "req_body_preview" -> result.add(HistoryQuery.IncludeField.REQUEST_BODY_PREVIEW);
                case "response_body_preview", "resp_body_preview" -> result.add(HistoryQuery.IncludeField.RESPONSE_BODY_PREVIEW);
                case "all" -> result.addAll(Set.of(HistoryQuery.IncludeField.values()));
                default -> throw ApiException.badRequest(
                        "invalid_include",
                        "include supports headers, cookies, query_parameters, body_preview, their request/response variants, or all"
                );
            }
        }
        return Set.copyOf(result);
    }

    private static HistoryQuery.OutputFormat parseOutputFormat(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "json" -> HistoryQuery.OutputFormat.JSON;
            case "ndjson", "jsonl" -> HistoryQuery.OutputFormat.NDJSON;
            default -> throw ApiException.badRequest("invalid_format", "format must be json or ndjson");
        };
    }

    private static HistoryQuery.SizeRange sizeRange(
            QueryParameters parameters,
            String[] minimumNames,
            String[] maximumNames,
            String label
    ) {
        Integer minimum = optionalInteger(parameters, 0, Integer.MAX_VALUE, minimumNames);
        Integer maximum = optionalInteger(parameters, 0, Integer.MAX_VALUE, maximumNames);
        if (minimum != null && maximum != null && minimum > maximum) {
            throw ApiException.badRequest("invalid_size_range", label + " minimum must not exceed maximum");
        }
        return new HistoryQuery.SizeRange(minimum, maximum);
    }

    private static Boolean optionalBoolean(QueryParameters parameters, String... names) {
        String value = singleAlias(parameters, names);
        return value == null ? null : parseBoolean(names[0], value);
    }

    private static boolean optionalBoolean(QueryParameters parameters, boolean defaultValue, String... names) {
        Boolean value = optionalBoolean(parameters, names);
        return value == null ? defaultValue : value;
    }

    private static boolean parseBoolean(String name, String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> throw ApiException.badRequest("invalid_boolean", name + " must be true or false");
        };
    }

    private static Integer optionalInteger(QueryParameters parameters, int minimum, int maximum, String... names) {
        String value = singleAlias(parameters, names);
        return value == null ? null : parseInteger(names[0], value, minimum, maximum);
    }

    private static int integerAlias(
            QueryParameters parameters,
            int defaultValue,
            int minimum,
            int maximum,
            String... names
    ) {
        Integer value = optionalInteger(parameters, minimum, maximum, names);
        return value == null ? defaultValue : value;
    }

    private static int parseInteger(String name, String value, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < minimum || parsed > maximum) {
                throw ApiException.badRequest(
                        "invalid_integer",
                        name + " must be between " + minimum + " and " + maximum
                );
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw ApiException.badRequest("invalid_integer", name + " must be an integer");
        }
    }

    private static Set<Integer> integerSet(List<String> values, String name, int minimum, int maximum) {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (String value : values) {
            result.add(parseInteger(name, value, minimum, maximum));
        }
        return Set.copyOf(result);
    }

    private static String firstAliasOrDefault(QueryParameters parameters, String defaultValue, String... names) {
        String value = singleAlias(parameters, names);
        return value == null ? defaultValue : value;
    }

    private static String singleAlias(QueryParameters parameters, String... names) {
        List<String> found = values(parameters, names).stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (found.isEmpty()) {
            return null;
        }
        String first = found.getFirst();
        if (found.stream().anyMatch(value -> !value.equals(first))) {
            throw ApiException.badRequest(
                    "conflicting_aliases",
                    "Equivalent parameters contain conflicting values: " + String.join(", ", names)
            );
        }
        return first;
    }

    private static List<String> values(QueryParameters parameters, String... names) {
        List<String> result = new ArrayList<>();
        for (String name : names) {
            result.addAll(parameters.all(name));
        }
        return result;
    }

    private static List<String> splitCsv(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    private static List<String> nonBlank(List<String> values) {
        return values.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private static Set<String> uppercaseSet(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value.toUpperCase(Locale.ROOT)));
        return result;
    }

    private static Set<String> lowercaseSet(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value.toLowerCase(Locale.ROOT)));
        return result;
    }

    private static Set<String> normalizeExtensions(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "")));
        return result;
    }

    private enum Boundary {
        START,
        END
    }

    private record TimeRange(Instant from, Instant to) {
    }
}
