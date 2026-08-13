/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.persistence.Preferences;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.proxy.ProxyHistoryFilter;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import com.burphistoryrest.config.ApiSettings;
import com.burphistoryrest.config.NetworkInterfaceCatalog;
import com.burphistoryrest.history.CursorCodec;
import com.burphistoryrest.history.HistoryQuery;
import com.burphistoryrest.history.HistoryQueryParser;
import com.burphistoryrest.history.HistoryService;
import com.burphistoryrest.history.RedactionPolicy;
import com.burphistoryrest.history.HistoryMapper;
import com.burphistoryrest.events.HistoryEventBroker;
import com.burphistoryrest.security.AccessTokenStore;
import com.burphistoryrest.telemetry.AuditLogger;
import com.burphistoryrest.telemetry.MetricsRegistry;
import com.burphistoryrest.history.StatusMatcher;
import com.burphistoryrest.server.ApiException;
import com.burphistoryrest.server.ApiServer;
import com.burphistoryrest.server.Auth;
import com.burphistoryrest.util.Json;
import com.burphistoryrest.util.QueryParameters;
import com.burphistoryrest.server.http.HttpHeaders;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.io.ByteArrayOutputStream;
import java.util.regex.Pattern;

/** Dependency-free smoke and integration tests. Run with ./gradlew check. */
public final class SelfTest {
    private static final AtomicInteger ASSERTIONS = new AtomicInteger();

    private SelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        testJson();
        testAuthentication();
        testTokenFallbackPersistence();
        testEventBrokerOrdering();
        testStatusMatcher();
        testNetworkBindingSettings();
        TestFixture fixture = fixture();
        testQueryParserAndMatcher(fixture.item());
        testLargeHistoryGuard(fixture);
        testServer(fixture);
        testWildcardServer(fixture);
        System.out.println("SelfTest passed: " + ASSERTIONS.get() + " assertions");
    }

    private static void testJson() {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("quote", "a\"b\\c\n");
        value.put("list", List.of(1, true, "x"));
        value.put("null", null);
        assertEquals(
                "{\"quote\":\"a\\\"b\\\\c\\n\",\"list\":[1,true,\"x\"],\"null\":null}",
                Json.stringify(value),
                "JSON escaping"
        );
    }

    private static void testAuthentication() {
        AccessTokenStore tokens = tokenStore(dynamicProxy(Logging.class, (p, m, a) -> objectOrDefault(p, m, a)));
        HttpHeaders headers = new HttpHeaders();
        assertTrue(Auth.authenticate(headers, tokens).isEmpty(), "missing bearer token");
        headers.set("Authorization", "Basic abc");
        assertTrue(Auth.authenticate(headers, tokens).isEmpty(), "wrong authentication scheme");
        headers.set("Authorization", "bearer " + token());
        assertTrue(Auth.authenticate(headers, tokens).isPresent(), "case-insensitive bearer scheme");
        headers.set("Authorization", "Bearer wrong");
        assertTrue(Auth.authenticate(headers, tokens).isEmpty(), "incorrect bearer token");
        headers.add("X-API-Key", token());
        assertTrue(Auth.authenticate(headers, tokens).isEmpty(), "ambiguous duplicate credentials rejected");
    }


    private static void testTokenFallbackPersistence() {
        Map<String, Object> values = new java.util.concurrent.ConcurrentHashMap<>();
        Preferences preferences = dynamicProxy(Preferences.class, (proxy, method, args) -> switch (method.getName()) {
            case "getString", "getInteger", "getBoolean" -> values.get(String.valueOf(args[0]));
            case "setString", "setInteger", "setBoolean" -> { values.put(String.valueOf(args[0]), args[1]); yield null; }
            default -> objectOrDefault(proxy, method, args);
        });
        Logging logging = dynamicProxy(Logging.class, (proxy, method, args) -> objectOrDefault(proxy, method, args));
        AccessTokenStore first = new AccessTokenStore(null, preferences, logging);
        String secret = first.bootstrapToken().orElseThrow().secret();
        assertTrue(first.authenticate(secret).isPresent(), "fallback token authenticates before reload");
        assertTrue(values.containsKey("burp_history_rest.access_tokens_fallback_v1"), "fallback token hash persisted");
        assertNotContains(String.valueOf(values.get("burp_history_rest.access_tokens_fallback_v1")), secret, "fallback store never persists plaintext secret");
        AccessTokenStore second = new AccessTokenStore(null, preferences, logging);
        assertTrue(second.bootstrapToken().isEmpty(), "persisted fallback does not generate another bootstrap token");
        assertTrue(second.authenticate(secret).isPresent(), "fallback token authenticates after reload");
    }

    private static void testEventBrokerOrdering() {
        MetricsRegistry metrics = new MetricsRegistry();
        HistoryEventBroker broker = new HistoryEventBroker("event-test", 3, metrics);
        Map<String, Object> first = new java.util.LinkedHashMap<>();
        first.put("url", null);
        broker.publish(100, first);
        broker.publish(99, Map.of("url", "https://example.test/slow"));
        HistoryEventBroker.Batch batch = broker.awaitAfter(0, 10, 0);
        assertEquals(2, batch.events().size(), "event broker retains out-of-order history completions");
        assertEquals(1L, batch.events().get(0).sequence(), "first event sequence");
        assertEquals(100, batch.events().get(0).historyId(), "first completion history ID");
        assertEquals(null, batch.events().get(0).item().get("url"), "event summaries preserve nullable fields");
        assertEquals(2L, batch.events().get(1).sequence(), "second event sequence");
        assertEquals(99, batch.events().get(1).historyId(), "second completion history ID");
        broker.publish(101, Map.of());
        broker.publish(102, Map.of());
        broker.publish(103, Map.of());
        assertThrows(ApiException.class, () -> broker.awaitAfter(1, 10, 0), "expired event cursor rejected after ring rollover");
    }

    private static void testStatusMatcher() {
        StatusMatcher matcher = StatusMatcher.parse(List.of("2xx,404,none"));
        assertTrue(matcher.matches(200), "2xx status class");
        assertTrue(matcher.matches(299), "2xx upper boundary");
        assertTrue(matcher.matches(404), "exact status");
        assertTrue(matcher.matches(null), "no-response status");
        assertFalse(matcher.matches(500), "nonmatching status");
        assertTrue(StatusMatcher.any().matches(500), "default status matcher");
    }

    private static void testNetworkBindingSettings() {
        ApiSettings defaults = ApiSettings.defaults();
        assertEquals(ApiSettings.DEFAULT_BIND_ADDRESS, defaults.bindAddress(), "default bind address");
        assertFalse(defaults.allowAllInterfaces(), "wildcard binding is disabled by default");
        assertFalse(defaults.listenSocketAddress().getAddress().isAnyLocalAddress(), "default socket is not wildcard");
        assertFalse(defaults.networkExposed(), "default loopback is not network exposed");
        assertTrue(NetworkInterfaceCatalog.addresses().stream()
                .anyMatch(option -> option.address().equals(ApiSettings.DEFAULT_BIND_ADDRESS)),
                "interface catalog includes IPv4 loopback");

        ApiSettings wildcard = withBinding(defaults, ApiSettings.DEFAULT_BIND_ADDRESS, true, defaults.port());
        assertTrue(wildcard.listenSocketAddress().getAddress().isAnyLocalAddress(), "all-interface mode uses wildcard socket");
        assertEquals("*", wildcard.listenHost(), "wildcard listener host marker");
        assertTrue(wildcard.networkExposed(), "all-interface mode reports network exposure");
        assertContains(wildcard.baseUrl(), "127.0.0.1", "wildcard mode keeps a connectable local dashboard URL");

        assertThrows(IllegalArgumentException.class,
                () -> withBinding(defaults, "localhost", false, defaults.port()),
                "host names are rejected for deterministic interface binding");
        assertThrows(IllegalArgumentException.class,
                () -> withBinding(defaults, "0.0.0.0", false, defaults.port()),
                "wildcard address requires the explicit checkbox");
    }

    private static void testQueryParserAndMatcher(ProxyHttpRequestResponse item) {
        ApiSettings settings = testSettings(8090, true);
        URI uri = URI.create(
                "/api/v1/history?host=*.example.com&method=POST&status=2xx&reason=OK&mime=JSON"
                        + "&path=%2Fapi%2F&url=api.example.com&query_param=debug%3Dtrue"
                        + "&request_header=X-Test%3Aalpha&response_header=X-Trace%3Atrace-123"
                        + "&cookie=session%3Dabc&response_cookie=access%3Dxyz"
                        + "&request_body=alice&response_body=access_token"
                        + "&q=access_token&search_in=response&in_scope=true"
                        + "&request_length_min=1&response_length_min=1"
                        + "&from=1784427000&to=1784428000000"
                        + "&include=headers,cookies,query_parameters,body_preview&sort=id&order=asc&limit=25"
        );
        HistoryQuery query = HistoryQueryParser.parse(QueryParameters.parse(uri), settings);
        assertTrue(query.matches(item), "combined history filters");
        assertEquals(25, query.limit(), "query page limit");
        assertEquals(HistoryQuery.SortField.ID, query.sortField(), "sort field");
        assertTrue(query.includeFields().contains(HistoryQuery.IncludeField.REQUEST_HEADERS), "include request headers");


        HistoryQuery dateQuery = HistoryQueryParser.parse(
                QueryParameters.parse(URI.create(
                        "/api/v1/history?date=2026-07-19&timezone=Asia%2FSingapore&sort=timestamp&order=newest"
                )),
                settings
        );
        assertTrue(dateQuery.matches(item), "date and timezone filter");
        assertEquals(HistoryQuery.SortField.TIME, dateQuery.sortField(), "timestamp sort alias");

        HistoryQuery miss = HistoryQueryParser.parse(
                QueryParameters.parse(URI.create("/api/v1/history?host=other.example&limit=10")),
                settings
        );
        assertFalse(miss.matches(item), "host mismatch");

        assertThrows(
                ApiException.class,
                () -> HistoryQueryParser.parse(
                        QueryParameters.parse(URI.create("/api/v1/history?q=a.*b&regex=true")),
                        settings
                ),
                "disabled regex rejected"
        );
        assertThrows(
                ApiException.class,
                () -> HistoryQueryParser.parse(
                        QueryParameters.parse(URI.create("/api/v1/history?unknown=value")),
                        settings
                ),
                "unknown parameter rejected"
        );
    }

    private static void testLargeHistoryGuard(TestFixture fixture) {
        ApiSettings base = testSettings(8090, true);
        ApiSettings bounded = new ApiSettings(
                base.bindAddress(), base.allowAllInterfaces(), base.port(), base.maxPageSize(), base.maxMessageMiB(),
                base.maxRequestBodyKiB(), 1_000, base.queryTimeoutMs(),
                base.workerThreads(), base.maxConcurrentQueries(), base.maxConcurrentEvents(), base.eventBufferSize(), base.rateLimitPerMinute(),
                base.rawAccessEnabled(), base.auditEnabled(), base.auditLogPath(), base.auditMaxMiB(), base.auditRetainedFiles(),
                base.autoStart(), base.regexEnabled(), base.redactionEnabled(), base.redactedHeaderNames(),
                base.redactedParameterNames(), base.redactionReplacement()
        );
        List<ProxyHttpRequestResponse> oversized = java.util.Collections.nCopies(1_001, fixture.item());
        burp.api.montoya.proxy.Proxy proxy = dynamicProxy(burp.api.montoya.proxy.Proxy.class, (p, m, a) ->
                m.getName().equals("history") ? oversized : objectOrDefault(p, m, a));
        MontoyaApi api = dynamicProxy(MontoyaApi.class, (p, m, a) -> switch (m.getName()) {
            case "proxy" -> proxy;
            case "logging" -> fixture.logging();
            default -> objectOrDefault(p, m, a);
        });
        HistoryService service = new HistoryService(api, bounded, "scan-test", new MetricsRegistry());
        HistoryQuery query = HistoryQueryParser.parse(QueryParameters.parse(URI.create("/api/v1/history?limit=1")), bounded);
        assertThrows(ApiException.class, () -> service.search(query), "large-history scan cap enforced");
    }

    private static void testServer(TestFixture fixture) throws Exception {
        int port = freePort();
        ApiSettings settings = testSettings(port, true);
        MetricsRegistry metrics = new MetricsRegistry();
        HistoryService historyService = new HistoryService(fixture.api(), settings, "test-instance", metrics);
        AccessTokenStore tokenStore = tokenStore(fixture.logging());
        String readOnlyToken = tokenStore.createDefaultReadToken("read-only-test").secret();
        HistoryEventBroker eventBroker = new HistoryEventBroker("test-instance", settings.eventBufferSize(), metrics);
        eventBroker.publish(42, HistoryMapper.summary(fixture.item(), new RedactionPolicy(settings)));
        AuditLogger audit = new AuditLogger(settings, fixture.logging(), metrics);
        ApiServer server = new ApiServer(settings, historyService, tokenStore, eventBroker, metrics, audit, fixture.logging());
        server.start();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        String base = settings.baseUrl();
        try {
            var health = send(client, "GET", base + "/api/v1/health", null, BodyHandlers.ofString());
            assertEquals(200, health.statusCode(), "health status");
            assertContains(health.body(), "\"status\":\"ok\"", "health JSON");
            assertContains(health.body(), BuildInfo.VERSION, "health version");
            assertContains(health.body(), BuildInfo.AUTHOR, "health author");
            assertContains(health.body(), BuildInfo.AUTHOR_URL, "health author URL");
            assertContains(health.body(), "\"bindAddress\":\"127.0.0.1\"", "health bind address");
            assertContains(health.body(), "\"allowAllInterfaces\":false", "health wildcard setting");

            var dashboard = send(client, "GET", base + "/ui/", null, BodyHandlers.ofString());
            assertEquals(200, dashboard.statusCode(), "dashboard status");
            assertContains(dashboard.body(), "Proxy history", "dashboard content");

            var capabilities = send(client, "GET", base + "/api/v1/capabilities", null, BodyHandlers.ofString());
            assertEquals(200, capabilities.statusCode(), "capabilities status");
            assertContains(capabilities.body(), "request_header", "capabilities filters");
            assertContains(capabilities.body(), BuildInfo.AUTHOR_DISPLAY, "capabilities author");

            var openApi = send(client, "GET", base + "/api/v1/openapi.json", null, BodyHandlers.ofString());
            assertEquals(200, openApi.statusCode(), "OpenAPI status");
            assertContains(openApi.body(), "\"openapi\": \"3.1.0\"", "OpenAPI document");
            assertContains(openApi.body(), Integer.toString(port), "OpenAPI server port replacement");

            var unauthorized = send(client, "GET", base + "/api/v1/history?limit=1", null, BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode(), "history authentication required");

            var wrongToken = send(client, "GET", base + "/api/v1/history?limit=1", "wrong", BodyHandlers.ofString());
            assertEquals(401, wrongToken.statusCode(), "wrong token rejected");

            String query = "/api/v1/history?host=*.example.com&method=POST&status=2xx"
                    + "&request_header=X-Test%3Aalpha&cookie=session%3Dabc&query_param=debug%3Dtrue"
                    + "&q=access_token&search_in=response&include=headers,cookies,query_parameters&limit=50";
            var page = send(client, "GET", base + query, token(), BodyHandlers.ofString());
            assertEquals(200, page.statusCode(), "history query status");
            assertContains(page.body(), "\"total\":1", "history matched count");
            assertContains(page.body(), "\"id\":42", "history item ID");
            assertContains(page.body(), "\"host\":\"api.example.com\"", "history host");
            assertContains(page.body(), "\"requestHeaders\"", "included request headers");
            assertContains(page.body(), "\"requestCookies\"", "included request cookies");
            assertContains(page.body(), "\"queryParameters\"", "included query parameters");
            assertContains(page.body(), "[REDACTED]", "page applies redaction");
            assertNotContains(page.body(), "request-secret", "page hides authorization secret");
            assertNotContains(page.body(), "session=abc", "page hides request cookie value");
            assertNotContains(page.body(), "access=xyz", "page hides response cookie value");

            var ndjson = send(
                    client,
                    "GET",
                    base + "/api/v1/history?format=ndjson&sort=id&order=asc&limit=10",
                    token(),
                    BodyHandlers.ofString()
            );
            assertEquals(200, ndjson.statusCode(), "NDJSON status");
            assertContains(ndjson.headers().firstValue("Content-Type").orElse(""), "application/x-ndjson", "NDJSON type");
            assertContains(ndjson.body(), "\"id\":42", "NDJSON item");

            var emptyPage = send(
                    client,
                    "GET",
                    base + "/api/v1/history?host=missing.example&limit=10",
                    token(),
                    BodyHandlers.ofString()
            );
            assertEquals(200, emptyPage.statusCode(), "empty history query status");
            assertContains(emptyPage.body(), "\"total\":0", "empty history result");

            var details = send(client, "GET", base + "/api/v1/history/42?format=text", token(), BodyHandlers.ofString());
            assertEquals(200, details.statusCode(), "history details status");
            assertContains(details.body(), "POST /api/login?debug=true&role=admin HTTP/1.1", "request details");
            assertContains(details.body(), "access_token", "response details");
            assertContains(details.body(), "[REDACTED]", "details applies redaction");
            assertNotContains(details.body(), "request-secret", "details hides authorization secret");
            assertNotContains(details.body(), "abc123", "details hides token value");

            var requestRaw = send(
                    client,
                    "GET",
                    base + "/api/v1/history/42/request?format=raw",
                    token(),
                    BodyHandlers.ofByteArray()
            );
            assertEquals(200, requestRaw.statusCode(), "raw request status");
            assertArrayEquals(fixture.requestBytes(), requestRaw.body(), "exact request bytes");
            assertEquals("false", requestRaw.headers().firstValue("X-Redaction-Applied").orElse(""), "raw redaction header");
            assertEquals("raw-unredacted", requestRaw.headers().firstValue("X-Sensitive-Data").orElse(""), "raw sensitivity header");

            var responseBase64 = send(
                    client,
                    "GET",
                    base + "/api/v1/history/42/response?format=base64",
                    token(),
                    BodyHandlers.ofString()
            );
            assertEquals(200, responseBase64.statusCode(), "base64 response status");
            assertContains(
                    responseBase64.body(),
                    Base64.getEncoder().encodeToString(new RedactionPolicy(settings).message(fixture.responseBytes())),
                    "redacted base64 response data"
            );

            var unknown = send(
                    client,
                    "GET",
                    base + "/api/v1/history?bogus=1",
                    token(),
                    BodyHandlers.ofString()
            );
            assertEquals(400, unknown.statusCode(), "unknown parameter status");
            assertContains(unknown.body(), "unknown_parameter", "unknown parameter error code");

            var missing = send(client, "GET", base + "/api/v1/history/999", token(), BodyHandlers.ofString());
            assertEquals(404, missing.statusCode(), "missing history item status");

            var incremental = send(
                    client,
                    "GET",
                    base + "/api/v1/history?after_id=0&limit=10",
                    token(),
                    BodyHandlers.ofString()
            );
            assertEquals(200, incremental.statusCode(), "incremental history status");
            assertContains(incremental.body(), "\"nextCursor\"", "incremental cursor metadata");
            assertContains(incremental.body(), "test-instance", "incremental instance ID");
            assertEquals("test-instance", incremental.headers().firstValue("X-Instance-Id").orElse(""), "incremental instance header");
            assertTrue(incremental.headers().firstValue("X-Next-Cursor").isPresent(), "incremental cursor header");

            var staleCursor = send(
                    client,
                    "GET",
                    base + "/api/v1/history?cursor=" + CursorCodec.encode("other-instance", 42),
                    token(),
                    BodyHandlers.ofString()
            );
            assertEquals(409, staleCursor.statusCode(), "stale cursor conflict status");
            assertContains(staleCursor.body(), "cursor_instance_mismatch", "stale cursor error code");

            String searchJson = """
                    {
                      "filters": {"host": "*.example.com", "method": ["POST"], "status": "2xx"},
                      "search": {"keywords": ["access_token"], "location": "response"},
                      "sort": {"field": "id", "order": "asc"},
                      "pagination": {"limit": 10},
                      "output": {"include": ["headers", "cookies"]}
                    }
                    """;
            var postSearch = sendWithBody(
                    client,
                    base + "/api/v1/history/search",
                    token(),
                    searchJson,
                    BodyHandlers.ofString()
            );
            assertEquals(200, postSearch.statusCode(), "POST search status");
            assertContains(postSearch.body(), "\"id\":42", "POST search result");
            assertContains(postSearch.body(), "[REDACTED]", "structured output redaction");
            assertNotContains(postSearch.body(), "request-secret", "POST search hides authorization secret");

            var unsupportedSearchType = send(
                    client,
                    "POST",
                    base + "/api/v1/history/search",
                    token(),
                    BodyHandlers.ofString()
            );
            assertEquals(415, unsupportedSearchType.statusCode(), "POST search content type required");

            var invalidSearchJson = sendWithBody(
                    client,
                    base + "/api/v1/history/search",
                    token(),
                    "{not-json}",
                    BodyHandlers.ofString()
            );
            assertEquals(400, invalidSearchJson.statusCode(), "invalid POST JSON status");

            var events = send(
                    client,
                    "GET",
                    base + "/api/v1/events?after_id=41&timeout=1&heartbeat=5&limit=10",
                    token(),
                    BodyHandlers.ofString()
            );
            assertEquals(200, events.statusCode(), "SSE event stream status");
            assertContains(events.headers().firstValue("Content-Type").orElse(""), "text/event-stream", "SSE content type");
            assertContains(events.body(), "event: ready", "SSE ready event");
            assertContains(events.body(), "event: history", "SSE history event");
            assertContains(events.body(), "id: 1", "SSE monotonic resumable sequence");
            assertContains(events.body(), "\"historyId\":42", "SSE history ID payload");
            assertContains(events.body(), "event: complete", "SSE completion event");
            assertContains(events.body(), "\"redacted\":true", "SSE marks redaction state");
            assertNotContains(events.body(), "request-secret", "SSE hides authorization secret");

            var forbiddenRaw = send(client, "GET", base + "/api/v1/history/42/request?format=raw", readOnlyToken, BodyHandlers.ofString());
            assertEquals(403, forbiddenRaw.statusCode(), "read-only token cannot access raw traffic");
            assertContains(forbiddenRaw.body(), "insufficient_scope", "raw scope error code");

            var metricsResponse = send(client, "GET", base + "/api/v1/metrics", token(), BodyHandlers.ofString());
            assertEquals(200, metricsResponse.statusCode(), "metrics status");
            assertContains(metricsResponse.body(), "requestsTotal", "metrics body");

            var auditResponse = send(client, "GET", base + "/api/v1/audit?limit=20", token(), BodyHandlers.ofString());
            assertEquals(200, auditResponse.statusCode(), "audit status");
            assertContains(auditResponse.body(), "rawDataReturned", "audit metadata");
            assertNotContains(auditResponse.body(), token(), "audit never logs token secret");

            String conflictingLength = rawHttp(port, "GET /api/v1/health HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\nContent-Length: 1\r\n\r\n");
            assertContains(conflictingLength, "400 Bad Request", "conflicting Content-Length rejected");
            String teCl = rawHttp(port, "POST /api/v1/history/search HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n0\r\n\r\n");
            assertContains(teCl, "400 Bad Request", "TE/CL ambiguity rejected");
            String noHost = rawHttp(port, "GET /api/v1/health HTTP/1.1\r\n\r\n");
            assertContains(noHost, "400 Bad Request", "missing Host rejected");
            String chunkedBody = "{\"filters\":{\"host\":\"*.example.com\"},\"pagination\":{\"limit\":1}}";
            String chunked = rawHttp(port, "POST /api/v1/history/search HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + token() + "\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n" + Integer.toHexString(chunkedBody.getBytes(StandardCharsets.UTF_8).length) + "\r\n" + chunkedBody + "\r\n0\r\n\r\n");
            assertContains(chunked, "200 OK", "chunked JSON request supported");
            assertContains(chunked, "\"id\":42", "chunked search result");

            var pool = Executors.newFixedThreadPool(4);
            try {
                List<Callable<Integer>> calls = new java.util.ArrayList<>();
                for (int i = 0; i < 160; i++) {
                    int index = i;
                    calls.add(() -> send(client, "GET",
                            index % 3 == 0 ? base + "/api/v1/health" : base + "/api/v1/history?limit=1",
                            index % 3 == 0 ? null : (index % 2 == 0 ? token() : readOnlyToken), BodyHandlers.ofString()).statusCode());
                }
                var results = pool.invokeAll(calls);
                assertTrue(results.stream().allMatch(f -> {
                    try { return f.get() == 200; } catch (Exception e) { return false; }
                }), "concurrent multi-client requests complete successfully");
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(5, TimeUnit.SECONDS);
            }
            assertEquals(0, ((Number) metrics.snapshot().get("activeRequests")).intValue(), "concurrent request metric returns to zero");

            var post = send(client, "POST", base + "/api/v1/history", token(), BodyHandlers.ofString());
            assertEquals(405, post.statusCode(), "write method rejected");
        } finally {
            server.stop();
        }
    }

    private static void testWildcardServer(TestFixture fixture) throws Exception {
        int port = freePort();
        ApiSettings settings = withBinding(testSettings(port, true), ApiSettings.DEFAULT_BIND_ADDRESS, true, port);
        MetricsRegistry metrics = new MetricsRegistry();
        HistoryService historyService = new HistoryService(fixture.api(), settings, "wildcard-test", metrics);
        AccessTokenStore tokenStore = tokenStore(fixture.logging());
        HistoryEventBroker eventBroker = new HistoryEventBroker("wildcard-test", settings.eventBufferSize(), metrics);
        AuditLogger audit = new AuditLogger(settings, fixture.logging(), metrics);
        ApiServer server = new ApiServer(settings, historyService, tokenStore, eventBroker, metrics, audit, fixture.logging());
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var health = send(client, "GET", settings.baseUrl() + "/api/v1/health", null, BodyHandlers.ofString());
            assertEquals(200, health.statusCode(), "wildcard listener accepts loopback connection");
            assertContains(health.body(), "\"allowAllInterfaces\":true", "wildcard health metadata");
            assertContains(health.body(), "\"bindHost\":\"*\"", "wildcard bind host metadata");
            assertContains(health.body(), "\"networkExposed\":true", "wildcard exposure metadata");
        } finally {
            server.stop();
            audit.close();
        }
    }

    private static <T> java.net.http.HttpResponse<T> send(
            HttpClient client,
            String method,
            String uri,
            String bearerToken,
            java.net.http.HttpResponse.BodyHandler<T> bodyHandler
    ) throws IOException, InterruptedException {
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        request.method(method, BodyPublishers.noBody());
        return client.send(request.build(), bodyHandler);
    }

    private static <T> java.net.http.HttpResponse<T> sendWithBody(
            HttpClient client,
            String uri,
            String bearerToken,
            String body,
            java.net.http.HttpResponse.BodyHandler<T> bodyHandler
    ) throws IOException, InterruptedException {
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        if (bearerToken != null) request.header("Authorization", "Bearer " + bearerToken);
        request.POST(BodyPublishers.ofString(body));
        return client.send(request.build(), bodyHandler);
    }

    private static ApiSettings testSettings(int port, boolean redactionEnabled) {
        return new ApiSettings(
                ApiSettings.DEFAULT_BIND_ADDRESS, false,
                port, 500, 10, 256, 200_000, 10_000,
                8, 4, 4, 1000, 10_000,
                true, true, System.getProperty("java.io.tmpdir") + "/burp-history-rest-selftest-audit.jsonl", 2, 2,
                false, false, redactionEnabled,
                ApiSettings.DEFAULT_REDACTED_HEADERS,
                ApiSettings.DEFAULT_REDACTED_PARAMETERS,
                ApiSettings.DEFAULT_REDACTION_REPLACEMENT
        );
    }

    private static ApiSettings withBinding(ApiSettings base, String bindAddress, boolean allowAllInterfaces, int port) {
        return new ApiSettings(
                bindAddress, allowAllInterfaces,
                port, base.maxPageSize(), base.maxMessageMiB(), base.maxRequestBodyKiB(), base.maxScanItems(),
                base.queryTimeoutMs(), base.workerThreads(), base.maxConcurrentQueries(), base.maxConcurrentEvents(),
                base.eventBufferSize(), base.rateLimitPerMinute(), base.rawAccessEnabled(), base.auditEnabled(),
                base.auditLogPath(), base.auditMaxMiB(), base.auditRetainedFiles(), base.autoStart(), base.regexEnabled(),
                base.redactionEnabled(), base.redactedHeaderNames(), base.redactedParameterNames(), base.redactionReplacement()
        );
    }

    private static AccessTokenStore tokenStore(Logging logging) {
        Map<String, Object> projectValues = new java.util.concurrent.ConcurrentHashMap<>();
        PersistedObject project = dynamicProxy(PersistedObject.class, (proxy, method, args) -> switch (method.getName()) {
            case "getString", "getInteger", "getBoolean" -> projectValues.get(String.valueOf(args[0]));
            case "setString", "setInteger", "setBoolean" -> { projectValues.put(String.valueOf(args[0]), args[1]); yield null; }
            default -> objectOrDefault(proxy, method, args);
        });
        Preferences preferences = dynamicProxy(Preferences.class, (proxy, method, args) -> switch (method.getName()) {
            case "getString" -> String.valueOf(args[0]).equals("burp_history_rest.token") ? token() : null;
            case "getInteger", "getBoolean" -> null;
            default -> objectOrDefault(proxy, method, args);
        });
        return new AccessTokenStore(project, preferences, logging);
    }

    private static TestFixture fixture() {
        byte[] requestBytes = (
                "POST /api/login?debug=true&role=admin HTTP/1.1\r\n"
                        + "Host: api.example.com\r\n"
                        + "Authorization: Bearer request-secret\r\n"
                        + "X-Test: alpha\r\n"
                        + "Cookie: session=abc; theme=dark\r\n"
                        + "Content-Type: application/json\r\n\r\n"
                        + "{\"username\":\"alice\"}"
        ).getBytes(StandardCharsets.UTF_8);
        byte[] responseBytes = (
                "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/json\r\n"
                        + "X-Trace: trace-123\r\n"
                        + "Set-Cookie: access=xyz; HttpOnly; Secure\r\n\r\n"
                        + "{\"access_token\":\"abc123\"}"
        ).getBytes(StandardCharsets.UTF_8);

        HttpService service = dynamicProxy(HttpService.class, (proxy, method, args) -> switch (method.getName()) {
            case "host" -> "api.example.com";
            case "port" -> 443;
            case "secure" -> true;
            default -> objectOrDefault(proxy, method, args);
        });
        HttpRequest request = dynamicProxy(HttpRequest.class, (proxy, method, args) -> switch (method.getName()) {
            case "httpService" -> service;
            case "url" -> "https://api.example.com/api/login?debug=true&role=admin";
            case "method" -> "POST";
            case "path" -> "/api/login?debug=true&role=admin";
            case "query" -> "debug=true&role=admin";
            case "pathWithoutQuery" -> "/api/login";
            case "fileExtension" -> "";
            case "isInScope" -> true;
            case "httpVersion" -> "HTTP/1.1";
            case "toByteArray" -> byteArray(requestBytes);
            case "contains" -> contains(requestBytes, args);
            default -> objectOrDefault(proxy, method, args);
        });
        HttpResponse response = dynamicProxy(HttpResponse.class, (proxy, method, args) -> switch (method.getName()) {
            case "statusCode" -> (short) 200;
            case "reasonPhrase" -> "OK";
            case "httpVersion" -> "HTTP/1.1";
            case "toByteArray" -> byteArray(responseBytes);
            case "contains" -> contains(responseBytes, args);
            default -> objectOrDefault(proxy, method, args);
        });
        ProxyHttpRequestResponse item = dynamicProxy(
                ProxyHttpRequestResponse.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "finalRequest" -> request;
                    case "response" -> response;
                    case "httpService" -> service;
                    case "edited" -> false;
                    case "time" -> ZonedDateTime.parse("2026-07-19T10:15:30+08:00[Asia/Singapore]");
                    case "listenerPort" -> 8080;
                    case "id" -> 42;
                    case "mimeType" -> MimeType.JSON;
                    case "hasResponse" -> true;
                    case "contains" -> contains(concat(requestBytes, responseBytes), args);
                    default -> objectOrDefault(proxy, method, args);
                }
        );

        Logging logging = dynamicProxy(Logging.class, (proxy, method, args) -> {
            if (method.getName().equals("logToError") && args != null && args.length > 0) {
                System.err.println("[extension-test] " + args[0]);
            }
            return objectOrDefault(proxy, method, args);
        });

        burp.api.montoya.proxy.Proxy burpProxy = dynamicProxy(
                burp.api.montoya.proxy.Proxy.class,
                (proxy, method, args) -> {
                    if (method.getName().equals("history")) {
                        if (args == null || args.length == 0) {
                            return List.of(item);
                        }
                        ProxyHistoryFilter filter = (ProxyHistoryFilter) args[0];
                        return filter.matches(item) ? List.of(item) : List.of();
                    }
                    return objectOrDefault(proxy, method, args);
                }
        );

        MontoyaApi api = dynamicProxy(MontoyaApi.class, (proxy, method, args) -> switch (method.getName()) {
            case "proxy" -> burpProxy;
            case "logging" -> logging;
            default -> objectOrDefault(proxy, method, args);
        });
        return new TestFixture(api, logging, item, requestBytes, responseBytes);
    }

    private static boolean contains(byte[] bytes, Object[] arguments) {
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (arguments[0] instanceof Pattern pattern) {
            return pattern.matcher(value).find();
        }
        String term = String.valueOf(arguments[0]);
        boolean caseSensitive = (Boolean) arguments[1];
        return caseSensitive
                ? value.contains(term)
                : value.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }

    private static ByteArray byteArray(byte[] bytes) {
        return dynamicProxy(ByteArray.class, (proxy, method, args) -> switch (method.getName()) {
            case "length" -> bytes.length;
            case "getBytes" -> bytes.clone();
            default -> objectOrDefault(proxy, method, args);
        });
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> T dynamicProxy(Class<T> type, InvocationHandler handler) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                handler
        );
    }

    private static Object objectOrDefault(Object proxy, Method method, Object[] arguments) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> proxy.getClass().getInterfaces()[0].getSimpleName() + "TestProxy";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> null;
            };
        }
        Class<?> returnType = method.getReturnType();
        if (!returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return false;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0.0f;
        if (returnType == double.class) return 0.0d;
        if (returnType == char.class) return '\0';
        return null;
    }

    private static String rawHttp(int port, String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = socket.getInputStream().read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static String token() {
        return "01234567890123456789012345678901";
    }

    private static void assertTrue(boolean value, String description) {
        ASSERTIONS.incrementAndGet();
        if (!value) throw new AssertionError(description + ": expected true");
    }

    private static void assertFalse(boolean value, String description) {
        ASSERTIONS.incrementAndGet();
        if (value) throw new AssertionError(description + ": expected false");
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        ASSERTIONS.incrementAndGet();
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual, String description) {
        ASSERTIONS.incrementAndGet();
        if (!java.util.Arrays.equals(expected, actual)) {
            throw new AssertionError(description + ": byte arrays differ");
        }
    }

    private static void assertContains(String value, String expectedSubstring, String description) {
        ASSERTIONS.incrementAndGet();
        if (!value.contains(expectedSubstring)) {
            throw new AssertionError(description + ": missing " + expectedSubstring + " in " + value);
        }
    }

    private static void assertNotContains(String value, String unexpectedSubstring, String description) {
        ASSERTIONS.incrementAndGet();
        if (value.contains(unexpectedSubstring)) {
            throw new AssertionError(description + ": unexpectedly contained " + unexpectedSubstring + " in " + value);
        }
    }

    private static void assertThrows(
            Class<? extends Throwable> expectedType,
            ThrowingRunnable operation,
            String description
    ) {
        ASSERTIONS.incrementAndGet();
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (expectedType.isInstance(throwable)) return;
            throw new AssertionError(description + ": wrong exception type " + throwable, throwable);
        }
        throw new AssertionError(description + ": expected exception " + expectedType.getSimpleName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record TestFixture(
            MontoyaApi api,
            Logging logging,
            ProxyHttpRequestResponse item,
            byte[] requestBytes,
            byte[] responseBytes
    ) {
        private TestFixture {
            requestBytes = requestBytes.clone();
            responseBytes = responseBytes.clone();
        }

        @Override
        public byte[] requestBytes() {
            return requestBytes.clone();
        }

        @Override
        public byte[] responseBytes() {
            return responseBytes.clone();
        }
    }
}
