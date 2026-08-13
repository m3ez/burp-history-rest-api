/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.history;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpMessage;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import com.burphistoryrest.config.ApiSettings;
import com.burphistoryrest.server.ApiException;
import com.burphistoryrest.util.Json;
import com.burphistoryrest.telemetry.MetricsRegistry;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

public final class HistoryService {
    private final MontoyaApi api;
    private final int maximumMessageBytes;
    private final int maximumScannedItems;
    private final int queryTimeoutMs;
    private final String instanceId;
    private final RedactionPolicy redaction;
    private final Semaphore querySlots;
    private final Semaphore eventSlots;
    private final int maximumEventStreams;
    private final MetricsRegistry metrics;

    public HistoryService(MontoyaApi api, ApiSettings settings, String instanceId, MetricsRegistry metrics) {
        this.api = api;
        this.maximumMessageBytes = settings.maxMessageBytes();
        this.maximumScannedItems = settings.maxScanItems();
        this.queryTimeoutMs = settings.queryTimeoutMs();
        this.instanceId = instanceId;
        this.redaction = new RedactionPolicy(settings);
        this.querySlots = new Semaphore(settings.maxConcurrentQueries(), true);
        this.eventSlots = new Semaphore(settings.maxConcurrentEvents(), true);
        this.maximumEventStreams = settings.maxConcurrentEvents();
        this.metrics = metrics;
    }

    public String instanceId() {
        return instanceId;
    }

    public RedactionPolicy redaction() {
        return redaction;
    }

    public SearchResult search(HistoryQuery query) {
        return withQuerySlot(() -> {
            long startedAt = System.nanoTime();
            long deadline = startedAt + queryTimeoutMs * 1_000_000L;
            List<ProxyHttpRequestResponse> history = List.copyOf(api.proxy().history());
            if (history.size() > maximumScannedItems) {
                throw ApiException.scanLimit(history.size(), maximumScannedItems);
            }

            List<ProxyHttpRequestResponse> matched = new ArrayList<>();
            int scanned = 0;
            int highWatermarkId = -1;
            for (ProxyHttpRequestResponse item : history) {
                scanned++;
                highWatermarkId = Math.max(highWatermarkId, safeId(item));
                if ((scanned & 63) == 0 && System.nanoTime() > deadline) {
                    throw ApiException.queryTimeout(queryTimeoutMs, scanned);
                }
                if (query.matches(item)) {
                    matched.add(item);
                }
            }
            if (System.nanoTime() > deadline) {
                throw ApiException.queryTimeout(queryTimeoutMs, scanned);
            }
            matched.sort(query.comparator());

            int total = matched.size();
            int start = Math.min(query.offset(), total);
            int end = Math.min(start + query.limit(), total);
            List<ProxyHttpRequestResponse> pageItems = matched.subList(start, end);
            List<Map<String, Object>> items = pageItems.stream()
                    .map(item -> HistoryMapper.summary(item, query.includeFields(), redaction))
                    .toList();
            Integer minimumReturnedId = pageItems.stream().mapToInt(HistoryService::safeId).filter(value -> value >= 0).min()
                    .stream().boxed().findFirst().orElse(null);
            Integer maximumReturnedId = pageItems.stream().mapToInt(HistoryService::safeId).filter(value -> value >= 0).max()
                    .stream().boxed().findFirst().orElse(null);
            String nextCursor = query.sortField() == HistoryQuery.SortField.ID
                    && query.sortOrder() == HistoryQuery.SortOrder.ASC
                    && maximumReturnedId != null
                    ? CursorCodec.encode(instanceId, maximumReturnedId)
                    : null;
            double durationMs = Math.round((System.nanoTime() - startedAt) / 100_000.0) / 10.0;
            return new SearchResult(
                    query,
                    total,
                    start,
                    end,
                    items,
                    durationMs,
                    Instant.now(),
                    scanned,
                    highWatermarkId,
                    minimumReturnedId,
                    maximumReturnedId,
                    nextCursor,
                    instanceId,
                    redaction.enabled()
            );
        });
    }

    public Map<String, Object> details(int id, HistoryMapper.MessageFormat format) {
        return withQuerySlot(() -> HistoryMapper.details(findById(id), format, maximumMessageBytes, redaction));
    }

    public Map<String, Object> messageEnvelope(
            int id,
            MessageSide side,
            HistoryMapper.MessageFormat format
    ) {
        return withQuerySlot(() -> HistoryMapper.envelope(
                message(findById(id), side), format, maximumMessageBytes, redaction
        ));
    }

    public RawMessage rawMessage(int id, MessageSide side) {
        return withQuerySlot(() -> {
            HttpMessage message = message(findById(id), side);
            byte[] bytes = HistoryMapper.exactBytes(message, maximumMessageBytes);
            String suffix = side == MessageSide.REQUEST ? "request" : "response";
            return new RawMessage(bytes, "burp-history-" + id + "-" + suffix + ".http");
        });
    }

    /** Poll-backed live event batch, sorted by increasing history ID. */
    public EventBatch eventsAfter(int afterId, int limit) {
        List<ProxyHttpRequestResponse> items = api.proxy().history(item -> safeId(item) > afterId);
        items = items.stream()
                .sorted(Comparator.comparingInt(HistoryService::safeId))
                .limit(limit)
                .toList();
        List<Map<String, Object>> summaries = items.stream()
                .map(item -> HistoryMapper.summary(item, redaction))
                .toList();
        int lastId = items.isEmpty() ? afterId : safeId(items.getLast());
        return new EventBatch(summaries, lastId, CursorCodec.encode(instanceId, lastId));
    }

    public void acquireEventSlot() {
        if (!eventSlots.tryAcquire()) {
            throw new ApiException(
                    429,
                    "event_stream_busy",
                    "The maximum number of live event streams is already connected",
                    Map.of("maximumStreams", maximumEventStreams),
                    Map.of("Retry-After", "5")
            );
        }
        metrics.eventConnected();
    }

    public void releaseEventSlot() {
        eventSlots.release();
        metrics.eventDisconnected();
    }

    private ProxyHttpRequestResponse findById(int id) {
        return api.proxy().history(item -> item.id() == id).stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("history_item_not_found", "No Proxy history item has ID " + id));
    }

    private static HttpMessage message(ProxyHttpRequestResponse item, MessageSide side) {
        if (side == MessageSide.REQUEST) {
            HttpRequest request = item.finalRequest();
            if (request == null) {
                throw ApiException.notFound("request_not_available", "The final request is not available");
            }
            return request;
        }

        if (!item.hasResponse() || item.response() == null) {
            throw ApiException.notFound("response_not_available", "This history item does not have a response");
        }
        HttpResponse response = item.response();
        return response;
    }

    private <T> T withQuerySlot(Supplier<T> operation) {
        if (!querySlots.tryAcquire()) {
            throw ApiException.busy();
        }
        metrics.queryStarted();
        boolean success = false;
        try {
            T result = operation.get();
            success = true;
            return result;
        } finally {
            metrics.queryFinished(success);
            querySlots.release();
        }
    }

    private static int safeId(ProxyHttpRequestResponse item) {
        try {
            return item.id();
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    public enum MessageSide {
        REQUEST,
        RESPONSE
    }

    public record RawMessage(byte[] bytes, String filename) {
        public RawMessage {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record EventBatch(List<Map<String, Object>> items, int lastId, String cursor) {
        public EventBatch {
            items = List.copyOf(items);
        }
    }

    public record SearchResult(
            HistoryQuery query,
            int total,
            int start,
            int end,
            List<Map<String, Object>> items,
            double durationMs,
            Instant generatedAt,
            int scannedCount,
            int highWatermarkId,
            Integer minimumReturnedId,
            Integer maximumReturnedId,
            String nextCursor,
            String instanceId,
            boolean redacted
    ) {
        public SearchResult {
            items = List.copyOf(items);
        }

        public Map<String, Object> jsonBody() {
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("generatedAt", generatedAt.toString());
            page.put("total", total);
            page.put("page", query.pageNumber());
            page.put("pageSize", query.limit());
            page.put("offset", query.offset());
            page.put("limit", query.limit());
            page.put("returned", items.size());
            page.put("hasMore", end < total);
            page.put("nextOffset", end < total ? end : null);
            page.put("previousOffset", query.offset() > 0 ? Math.max(0, query.offset() - query.limit()) : null);
            page.put("sort", query.sortField().apiName());
            page.put("order", query.sortOrder().name().toLowerCase(java.util.Locale.ROOT));
            page.put("durationMs", durationMs);
            page.put("scannedCount", scannedCount);
            page.put("redacted", redacted);
            page.put("filters", query.describe());
            page.put("sync", syncMetadata());
            page.put("items", items);
            return page;
        }

        public byte[] ndjsonBytes() {
            StringBuilder output = new StringBuilder(Math.max(256, items.size() * 512));
            for (Map<String, Object> item : items) {
                output.append(Json.stringify(item)).append('\n');
            }
            return output.toString().getBytes(StandardCharsets.UTF_8);
        }

        public Map<String, String> paginationHeaders() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Total-Count", Integer.toString(total));
            headers.put("X-Returned-Count", Integer.toString(items.size()));
            headers.put("X-Page", Integer.toString(query.pageNumber()));
            headers.put("X-Page-Size", Integer.toString(query.limit()));
            headers.put("X-Offset", Integer.toString(query.offset()));
            headers.put("X-Has-More", Boolean.toString(end < total));
            headers.put("X-Sort", query.sortField().apiName());
            headers.put("X-Sort-Order", query.sortOrder().name().toLowerCase(java.util.Locale.ROOT));
            headers.put("X-Scanned-Count", Integer.toString(scannedCount));
            headers.put("X-Instance-Id", instanceId);
            headers.put("X-High-Watermark-Id", Integer.toString(highWatermarkId));
            headers.put("X-Redaction-Applied", Boolean.toString(redacted));
            if (nextCursor != null) headers.put("X-Next-Cursor", nextCursor);
            return headers;
        }

        private Map<String, Object> syncMetadata() {
            Map<String, Object> sync = new LinkedHashMap<>();
            sync.put("instanceId", instanceId);
            sync.put("highWatermarkId", highWatermarkId);
            sync.put("minimumReturnedId", minimumReturnedId);
            sync.put("maximumReturnedId", maximumReturnedId);
            sync.put("nextCursor", nextCursor);
            sync.put("cursorCompatible", query.sortField() == HistoryQuery.SortField.ID
                    && query.sortOrder() == HistoryQuery.SortOrder.ASC);
            return sync;
        }
    }
}
