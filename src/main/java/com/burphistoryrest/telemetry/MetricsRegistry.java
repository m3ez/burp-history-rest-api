/* Author: Supakiad S. (m3ez) - E-CQURITY (Thailand) | http://x.com/supakiad_mee */
package com.burphistoryrest.telemetry;

import com.burphistoryrest.BuildInfo;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Lock-free process metrics for diagnostics and capacity planning. */
public final class MetricsRegistry {
    private final Instant startedAt = Instant.now();
    private final LongAdder requests = new LongAdder();
    private final LongAdder authFailures = new LongAdder();
    private final LongAdder forbidden = new LongAdder();
    private final LongAdder rateLimited = new LongAdder();
    private final LongAdder queries = new LongAdder();
    private final LongAdder queryErrors = new LongAdder();
    private final LongAdder rawDownloads = new LongAdder();
    private final LongAdder responseBytes = new LongAdder();
    private final LongAdder eventsPublished = new LongAdder();
    private final LongAdder eventsDropped = new LongAdder();
    private final LongAdder auditFailures = new LongAdder();
    private final AtomicInteger activeRequests = new AtomicInteger();
    private final AtomicInteger activeQueries = new AtomicInteger();
    private final AtomicInteger activeEvents = new AtomicInteger();
    private final ConcurrentHashMap<Integer, LongAdder> statuses = new ConcurrentHashMap<>();

    public void requestStarted() { requests.increment(); activeRequests.incrementAndGet(); }
    public void requestFinished(int status, long bytes) {
        activeRequests.decrementAndGet(); statuses.computeIfAbsent(status, ignored -> new LongAdder()).increment();
        if (bytes > 0) responseBytes.add(bytes);
    }
    public void authFailure() { authFailures.increment(); }
    public void forbidden() { forbidden.increment(); }
    public void rateLimited() { rateLimited.increment(); }
    public void queryStarted() { queries.increment(); activeQueries.incrementAndGet(); }
    public void queryFinished(boolean success) { activeQueries.decrementAndGet(); if (!success) queryErrors.increment(); }
    public void eventConnected() { activeEvents.incrementAndGet(); }
    public void eventDisconnected() { activeEvents.decrementAndGet(); }
    public void eventPublished() { eventsPublished.increment(); }
    public void eventDropped(long count) { if (count > 0) eventsDropped.add(count); }
    public void rawDownload() { rawDownloads.increment(); }
    public void auditFailure() { auditFailures.increment(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("startedAt", startedAt.toString());
        m.put("uptimeSeconds", Math.max(0, java.time.Duration.between(startedAt, Instant.now()).toSeconds()));
        m.put("requestsTotal", requests.sum()); m.put("activeRequests", activeRequests.get());
        m.put("authenticationFailures", authFailures.sum()); m.put("forbiddenTotal", forbidden.sum());
        m.put("rateLimitedTotal", rateLimited.sum()); m.put("queriesTotal", queries.sum());
        m.put("queryErrorsTotal", queryErrors.sum()); m.put("activeQueries", activeQueries.get());
        m.put("activeEventStreams", activeEvents.get()); m.put("rawDownloadsTotal", rawDownloads.sum());
        m.put("responseBytesTotal", responseBytes.sum()); m.put("eventsPublishedTotal", eventsPublished.sum());
        m.put("eventsDroppedTotal", eventsDropped.sum()); m.put("auditFailuresTotal", auditFailures.sum());
        Map<String, Long> byStatus = new LinkedHashMap<>();
        statuses.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> byStatus.put(Integer.toString(e.getKey()), e.getValue().sum()));
        m.put("responsesByStatus", byStatus);
        return m;
    }

    public String prometheus() {
        StringBuilder b = new StringBuilder();
        gauge(b, "burp_history_rest_info", 1, "version", BuildInfo.VERSION);
        counter(b, "burp_history_rest_requests_total", requests.sum());
        gauge(b, "burp_history_rest_active_requests", activeRequests.get());
        counter(b, "burp_history_rest_authentication_failures_total", authFailures.sum());
        counter(b, "burp_history_rest_forbidden_total", forbidden.sum());
        counter(b, "burp_history_rest_rate_limited_total", rateLimited.sum());
        counter(b, "burp_history_rest_queries_total", queries.sum());
        counter(b, "burp_history_rest_query_errors_total", queryErrors.sum());
        gauge(b, "burp_history_rest_active_queries", activeQueries.get());
        gauge(b, "burp_history_rest_active_event_streams", activeEvents.get());
        counter(b, "burp_history_rest_raw_downloads_total", rawDownloads.sum());
        counter(b, "burp_history_rest_response_bytes_total", responseBytes.sum());
        counter(b, "burp_history_rest_events_published_total", eventsPublished.sum());
        counter(b, "burp_history_rest_events_dropped_total", eventsDropped.sum());
        b.append("# TYPE burp_history_rest_responses_total counter\n");
        statuses.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> b.append("burp_history_rest_responses_total{status=\"")
                        .append(entry.getKey()).append("\"} ").append(entry.getValue().sum()).append('\n'));
        return b.toString();
    }

    private static void counter(StringBuilder b, String name, long value) { b.append("# TYPE ").append(name).append(" counter\n").append(name).append(' ').append(value).append('\n'); }
    private static void gauge(StringBuilder b, String name, long value) { b.append("# TYPE ").append(name).append(" gauge\n").append(name).append(' ').append(value).append('\n'); }
    private static void gauge(StringBuilder b, String name, long value, String label, String labelValue) {
        b.append("# TYPE ").append(name).append(" gauge\n").append(name).append('{').append(label).append("=\"").append(labelValue).append("\"} ").append(value).append('\n');
    }
}
