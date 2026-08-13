/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.server;

import burp.api.montoya.logging.Logging;
import com.burphistoryrest.BuildInfo;
import com.burphistoryrest.config.ApiSettings;
import com.burphistoryrest.events.HistoryEventBroker;
import com.burphistoryrest.events.EventCursorCodec;
import com.burphistoryrest.history.CursorCodec;
import com.burphistoryrest.history.HistoryMapper;
import com.burphistoryrest.history.HistoryQuery;
import com.burphistoryrest.history.HistoryQueryParser;
import com.burphistoryrest.history.HistoryService;
import com.burphistoryrest.security.AccessScope;
import com.burphistoryrest.security.AccessTokenStore;
import com.burphistoryrest.security.ApiPrincipal;
import com.burphistoryrest.security.RateLimiter;
import com.burphistoryrest.server.http.HttpExchange;
import com.burphistoryrest.server.http.SimpleHttpServer;
import com.burphistoryrest.telemetry.AuditLogger;
import com.burphistoryrest.telemetry.MetricsRegistry;
import com.burphistoryrest.util.Json;
import com.burphistoryrest.util.QueryParameters;
import com.burphistoryrest.util.Resources;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ApiServer {
    private static final Pattern HISTORY_ITEM_PATH = Pattern.compile("^/api/v1/history/(\\d+)(?:/(request|response))?/?$");
    private final ApiSettings settings;
    private final HistoryService historyService;
    private final AccessTokenStore tokens;
    private final HistoryEventBroker events;
    private final MetricsRegistry metrics;
    private final AuditLogger audit;
    private final RateLimiter rateLimiter;
    private final Logging logging;
    private final byte[] dashboardHtml;
    private final byte[] openApiJson;
    private SimpleHttpServer server;

    public ApiServer(ApiSettings settings, HistoryService historyService, AccessTokenStore tokens,
                     HistoryEventBroker events, MetricsRegistry metrics, AuditLogger audit, Logging logging) throws IOException {
        this.settings = settings; this.historyService = historyService; this.tokens = tokens; this.events = events;
        this.metrics = metrics; this.audit = audit; this.logging = logging;
        this.rateLimiter = new RateLimiter(settings.rateLimitPerMinute());
        this.dashboardHtml = Resources.read("/ui/index.html");
        this.openApiJson = Resources.readUtf8("/openapi.json")
                .replace("{{BASE_URL}}", settings.baseUrl()).getBytes(StandardCharsets.UTF_8);
    }

    public synchronized void start() throws IOException {
        if (server != null) return;
        SimpleHttpServer created = new SimpleHttpServer(settings.listenSocketAddress(), 128,
                this::route, new DaemonThreadFactory(), settings.maxRequestBodyBytes(), settings.workerThreads(),
                settings.allowAllInterfaces());
        created.start(); server = created;
    }
    public synchronized void stop() { SimpleHttpServer current=server; server=null; if(current!=null)current.stop(); }

    private void route(HttpExchange exchange) {
        String path = exchange.requestUri().getPath();
        if (path.equals("/api/v1/health")) execute(exchange, null, Set.of("GET","HEAD"), false, this::handleHealth);
        else if (path.equals("/api/v1/capabilities")) execute(exchange, null, Set.of("GET","HEAD"), false, this::handleCapabilities);
        else if (path.equals("/api/v1/openapi.json")) execute(exchange, null, Set.of("GET","HEAD"), false, this::handleOpenApi);
        else if (path.equals("/api/v1/metrics")) execute(exchange, AccessScope.METRICS_READ, Set.of("GET","HEAD"), false, this::handleMetrics);
        else if (path.equals("/api/v1/audit")) execute(exchange, AccessScope.AUDIT_READ, Set.of("GET","HEAD"), false, this::handleAudit);
        else if (path.equals("/api/v1/history/search") || path.equals("/api/v1/history/search/"))
            execute(exchange, AccessScope.HISTORY_READ, Set.of("POST"), false, this::handleHistorySearchPost);
        else if (path.equals("/api/v1/events") || path.equals("/api/v1/events/"))
            execute(exchange, AccessScope.HISTORY_EVENTS, Set.of("GET"), false, this::handleEvents);
        else if (path.equals("/api/v1/history") || path.equals("/api/v1/history/") || path.startsWith("/api/v1/history/")) {
            boolean raw = isRawMessageRequest(exchange);
            execute(exchange, raw ? AccessScope.HISTORY_RAW : AccessScope.HISTORY_READ, Set.of("GET","HEAD"), raw, this::handleHistory);
        } else execute(exchange, null, Set.of("GET","HEAD"), false, this::handleStatic);
    }

    private boolean isRawMessageRequest(HttpExchange exchange) {
        Matcher m=HISTORY_ITEM_PATH.matcher(exchange.requestUri().getPath());
        if(!m.matches()||m.group(2)==null)return false;
        return QueryParameters.parse(exchange.requestUri()).firstOrDefault("format","raw").equalsIgnoreCase("raw");
    }

    private void execute(HttpExchange exchange, AccessScope required, Set<String> allowed, boolean raw, ExchangeHandler handler) {
        metrics.requestStarted(); ApiPrincipal principal=null;
        try {
            String method=exchange.requestMethod().toUpperCase(Locale.ROOT);
            if(!allowed.contains(method)){HttpResponses.methodNotAllowed(exchange,allowed);return;}
            if(required!=null){
                principal=Auth.authenticate(exchange.requestHeaders(),tokens).orElse(null);
                if(principal==null){metrics.authFailure();HttpResponses.unauthorized(exchange);return;}
                if(!principal.has(required)){metrics.forbidden();HttpResponses.forbidden(exchange,required.wireName());return;}
                RateLimiter.Decision decision=rateLimiter.allow(principal.id());
                if(!decision.allowed()){
                    metrics.rateLimited();
                    throw new ApiException(429,"rate_limited","The token exceeded its per-minute request limit",
                            Map.of("limitPerMinute",settings.rateLimitPerMinute()),Map.of("Retry-After",Integer.toString(decision.retryAfterSeconds())));
                }
            }
            handler.handle(exchange);
        } catch(ApiException e){if(exchange.responded())safeClose(exchange);else sendApiException(exchange,e);}
        catch(IllegalArgumentException e){if(exchange.responded())safeClose(exchange);else sendApiException(exchange,ApiException.badRequest("invalid_request",e.getMessage()));}
        catch(IOException e){logging.logToError("REST API I/O error",e);safeClose(exchange);}
        catch(RuntimeException e){
            logging.logToError("Unhandled REST API error",e);
            if(exchange.responded())safeClose(exchange);else try{HttpResponses.json(exchange,500,Map.of("error",Map.of("code","internal_error","message","The extension encountered an internal error")));}catch(IOException ignored){safeClose(exchange);}
        } finally {
            int status=exchange.responseStatus(); metrics.requestFinished(status,exchange.responseBytes());
            audit.record(exchange.requestId(),exchange.remoteAddress(),principal,exchange.requestMethod(),exchange.requestUri().getPath(),status,
                    exchange.durationMs(),exchange.responseBytes(),raw && status==200);
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        Map<String,Object> b=new LinkedHashMap<>(); b.put("status","ok");b.put("extension",BuildInfo.NAME);b.put("version",BuildInfo.VERSION);
        b.put("apiVersion",BuildInfo.API_VERSION);b.put("montoyaApi",BuildInfo.MONTOYA_API_VERSION);b.put("instanceId",historyService.instanceId());
        b.put("author",Map.of("name",BuildInfo.AUTHOR,"organization",BuildInfo.ORGANIZATION,"url",BuildInfo.AUTHOR_URL));
        b.put("bindHost",settings.listenHost());b.put("bindAddress",settings.bindAddress());
        b.put("allowAllInterfaces",settings.allowAllInterfaces());b.put("networkExposed",settings.networkExposed());
        b.put("listener",settings.listenerDescription());b.put("port",settings.port());b.put("baseUrl",settings.baseUrl());
        b.put("regexEnabled",settings.regexEnabled());b.put("maxPageSize",settings.maxPageSize());
        b.put("production",Map.of("eventDrivenStreaming",true,"projectScopedTokens",tokens.projectScoped(),"tokenStorage",tokens.storageMode(),"scopedAuthorization",true,
                "auditLogging",settings.auditEnabled(),"rawAccessEnabled",settings.rawAccessEnabled(),"boundedWorkerQueue",true,"rateLimitPerMinute",settings.rateLimitPerMinute()));
        b.put("eventBuffer",Map.of("size",events.size(),"capacity",settings.eventBufferSize(),"earliestSequence",events.earliestSequence(),"latestSequence",events.latestSequence()));
        b.put("redaction",historyService.redaction().describe());b.put("tokenCount",tokens.list().size());
        HttpResponses.json(exchange,200,b);
    }

    private void handleCapabilities(HttpExchange exchange) throws IOException {
        Map<String,Object> b=new LinkedHashMap<>();b.put("apiVersion",BuildInfo.API_VERSION);b.put("instanceId",historyService.instanceId());
        b.put("extension",Map.of("name",BuildInfo.NAME,"version",BuildInfo.VERSION,"author",BuildInfo.AUTHOR_DISPLAY,"authorUrl",BuildInfo.AUTHOR_URL));
        b.put("endpoints",Map.of("history","GET /api/v1/history","search","POST /api/v1/history/search","events","GET /api/v1/events",
                "metrics","GET /api/v1/metrics","audit","GET /api/v1/audit","details","GET /api/v1/history/{id}",
                "rawRequest","GET /api/v1/history/{id}/request?format=raw","rawResponse","GET /api/v1/history/{id}/response?format=raw"));
        b.put("scopes",AccessScope.wireNames()); b.put("rawAccessEnabled",settings.rawAccessEnabled());
        b.put("tokenStorage",tokens.storageMode());
        b.put("networkBinding",Map.of("bindAddress",settings.bindAddress(),"allowAllInterfaces",settings.allowAllInterfaces(),
                "listenHost",settings.listenHost(),"listener",settings.listenerDescription(),"networkExposed",settings.networkExposed(),
                "localDashboardUrl",settings.baseUrl()));
        b.put("authenticationHeaders",List.of("Authorization: Bearer <token>","X-API-Key: <token>"));
        b.put("outputFormats",List.of("json","ndjson"));b.put("messageFormats",List.of("raw","base64","text"));
        b.put("filters", List.of("id","id_from","id_to","after_id","before_id","cursor","host","port","protocol","method","url","path","query_string","query_param","status","reason","mime","extension","listener_port","has_response","edited","in_scope","request_header","response_header","header","request_cookie","response_cookie","cookie","request_body","response_body","body","q","keywords","search_in","keyword_mode","regex","case_sensitive","date","last","from","to","timezone","sort","order","offset","limit","page","page_size","format","include"));
        b.put("events",Map.of("implementation","Montoya response-handler backed bounded ring","resume", "after_id is an initial history high-watermark; cursor and Last-Event-ID use the monotonic event sequence",
                "maximumConcurrentStreams",settings.maxConcurrentEvents(),"bufferCapacity",settings.eventBufferSize()));
        b.put("safety",Map.of("workerThreads",settings.workerThreads(),"maximumConcurrentQueries",settings.maxConcurrentQueries(),
                "maximumRequestBodyBytes",settings.maxRequestBodyBytes(),"maximumScannedItems",settings.maxScanItems(),
                "queryTimeoutMs",settings.queryTimeoutMs(),"maximumPageSize",settings.maxPageSize(),"rateLimitPerMinute",settings.rateLimitPerMinute()));
        b.put("redaction",historyService.redaction().describe());HttpResponses.json(exchange,200,b);
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        QueryParameters p=QueryParameters.parse(exchange.requestUri());rejectUnknown(p,Set.of("format"));
        if(p.firstOrDefault("format","json").equalsIgnoreCase("prometheus"))
            HttpResponses.bytes(exchange,200,"text/plain; version=0.0.4; charset=UTF-8",metrics.prometheus().getBytes(StandardCharsets.UTF_8),Map.of());
        else HttpResponses.json(exchange,200,metrics.snapshot());
    }

    private void handleAudit(HttpExchange exchange) throws IOException {
        QueryParameters p=QueryParameters.parse(exchange.requestUri());rejectUnknown(p,Set.of("limit"));
        int limit=integer(p,"limit",200,1,2000); List<Map<String,Object>> items=audit.recent(limit); HttpResponses.json(exchange,200,Map.of("items",items,"returned",items.size()));
    }

    private void handleOpenApi(HttpExchange exchange)throws IOException{HttpResponses.bytes(exchange,200,"application/vnd.oai.openapi+json;version=3.1; charset=UTF-8",openApiJson,Map.of());}
    private void handleStatic(HttpExchange exchange)throws IOException{
        String path=exchange.requestUri().getPath();if(path.equals("/")||path.equals("/ui")){HttpResponses.redirect(exchange,"/ui/");return;}
        if(path.equals("/ui/")||path.equals("/ui/index.html")){HttpResponses.html(exchange,200,dashboardHtml);return;}
        throw ApiException.notFound("not_found","No endpoint exists at "+path);
    }

    private void handleHistorySearchPost(HttpExchange exchange)throws IOException{
        String ct=exchange.requestHeaders().first("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if(!ct.startsWith("application/json"))throw new ApiException(415,"unsupported_media_type","POST search requires Content-Type: application/json");
        if(exchange.requestBody().length==0)throw ApiException.badRequest("empty_search_body","POST search requires a JSON request body");
        HistoryQuery q=HistoryQueryParser.parse(SearchRequestParser.parse(exchange.requestBody(),QueryParameters.parse(exchange.requestUri())),settings,historyService.instanceId());
        sendSearchResult(exchange,historyService.search(q));
    }

    private void handleHistory(HttpExchange exchange)throws IOException{
        String path=exchange.requestUri().getPath();
        if(path.equals("/api/v1/history")||path.equals("/api/v1/history/")){
            HistoryQuery q=HistoryQueryParser.parse(QueryParameters.parse(exchange.requestUri()),settings,historyService.instanceId());sendSearchResult(exchange,historyService.search(q));return;
        }
        Matcher m=HISTORY_ITEM_PATH.matcher(path);if(!m.matches())throw ApiException.notFound("not_found","No history endpoint exists at "+path);
        int id;try{id=Integer.parseInt(m.group(1));}catch(NumberFormatException e){throw ApiException.badRequest("invalid_id","History ID must be a 32-bit integer");}
        String side=m.group(2);QueryParameters p=QueryParameters.parse(exchange.requestUri());rejectUnknown(p,Set.of("format"));
        if(side==null){HistoryMapper.MessageFormat f=HistoryMapper.MessageFormat.parse(p.firstOrDefault("format","base64"));
            HttpResponses.json(exchange,200,historyService.details(id,f),Map.of("X-Redaction-Applied",Boolean.toString(historyService.redaction().enabled())));return;}
        HistoryService.MessageSide messageSide=side.equals("request")?HistoryService.MessageSide.REQUEST:HistoryService.MessageSide.RESPONSE;
        String format=p.firstOrDefault("format","raw").toLowerCase(Locale.ROOT);
        if(format.equals("raw")){
            if(!settings.rawAccessEnabled())throw new ApiException(403,"raw_access_disabled","Exact raw message endpoints are disabled in project settings");
            HistoryService.RawMessage raw=historyService.rawMessage(id,messageSide);metrics.rawDownload();
            HttpResponses.bytes(exchange,200,"application/octet-stream",raw.bytes(),Map.of("Content-Disposition","inline; filename=\""+raw.filename()+"\"",
                    "X-Redaction-Applied","false","X-Sensitive-Data","raw-unredacted"));return;
        }
        HistoryMapper.MessageFormat f=HistoryMapper.MessageFormat.parse(format);
        HttpResponses.json(exchange,200,historyService.messageEnvelope(id,messageSide,f),Map.of("X-Redaction-Applied",Boolean.toString(historyService.redaction().enabled())));
    }

    private void sendSearchResult(HttpExchange exchange,HistoryService.SearchResult result)throws IOException{
        if(result.query().outputFormat()==HistoryQuery.OutputFormat.NDJSON)HttpResponses.ndjson(exchange,200,result.ndjsonBytes(),result.paginationHeaders());
        else HttpResponses.json(exchange,200,result.jsonBody(),result.paginationHeaders());
    }

    private void handleEvents(HttpExchange exchange)throws IOException{
        QueryParameters p=QueryParameters.parse(exchange.requestUri());rejectUnknown(p,Set.of("after_id","cursor","timeout","heartbeat","limit"));
        EventPosition position=eventPosition(exchange,p);int timeout=integer(p,"timeout",300,1,3600);int heartbeat=integer(p,"heartbeat",15,5,60);
        int limit=integer(p,"limit",100,1,Math.min(500,settings.maxPageSize()));historyService.acquireEventSlot();
        try{
            OutputStream out=exchange.startStream(200,"text/event-stream; charset=UTF-8",Map.of("Cache-Control","no-store","X-Accel-Buffering","no",
                    "X-Instance-Id",historyService.instanceId(),"X-Redaction-Applied",Boolean.toString(historyService.redaction().enabled())));
            long deadline=System.nanoTime()+timeout*1_000_000_000L;long cursorSequence=position.sequence();
            writeSse(out,null,"ready",Map.of("instanceId",historyService.instanceId(),"afterId",position.initialHistoryId(),
                    "eventSequence",cursorSequence,"generatedAt",Instant.now().toString(),"earliestAvailableSequence",events.earliestSequence(),
                    "latestAvailableSequence",events.latestSequence(),"redacted",historyService.redaction().enabled()));out.flush();
            while(System.nanoTime()<deadline&&!Thread.currentThread().isInterrupted()){
                long remainingMs=Math.max(1,(deadline-System.nanoTime())/1_000_000L);
                HistoryEventBroker.Batch batch=events.awaitAfter(cursorSequence,limit,Math.min(heartbeat*1000L,remainingMs));
                if(batch.events().isEmpty()){out.write((": heartbeat "+Instant.now()+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();continue;}
                for(HistoryEventBroker.Event event:batch.events()){
                    cursorSequence=event.sequence();
                    if(position.initialHistoryId()>=0&&event.historyId()<=position.initialHistoryId())continue;
                    writeSse(out,Long.toString(event.sequence()),"history",Map.of("instanceId",historyService.instanceId(),
                            "cursor",EventCursorCodec.encode(historyService.instanceId(),event.sequence()),"eventSequence",event.sequence(),
                            "historyId",event.historyId(),"item",event.item()));
                }out.flush();
            }
            writeSse(out,null,"complete",Map.of("instanceId",historyService.instanceId(),"eventSequence",cursorSequence,
                    "cursor",EventCursorCodec.encode(historyService.instanceId(),cursorSequence),"reason","timeout"));out.flush();
        }finally{historyService.releaseEventSlot();exchange.close();}
    }

    private EventPosition eventPosition(HttpExchange exchange,QueryParameters p){
        String cursor=p.first("cursor").orElse(null),after=p.first("after_id").orElse(null),last=exchange.requestHeaders().first("Last-Event-ID").orElse(null);
        int supplied=(cursor==null?0:1)+(after==null?0:1)+(last==null?0:1);if(supplied>1)throw ApiException.badRequest("conflicting_event_cursor","Use only one of cursor, after_id, or Last-Event-ID");
        if(cursor!=null)return new EventPosition(EventCursorCodec.decode(cursor,historyService.instanceId()),-1);
        if(last!=null){try{long sequence=Long.parseLong(last.trim());if(sequence<0)throw new NumberFormatException();return new EventPosition(sequence,-1);}catch(NumberFormatException e){throw ApiException.badRequest("invalid_event_cursor","Last-Event-ID must be a non-negative event sequence");}}
        int historyId=0;if(after!=null)try{historyId=Integer.parseInt(after.trim());if(historyId<0)throw new NumberFormatException();}catch(NumberFormatException e){throw ApiException.badRequest("invalid_event_cursor","after_id must be a non-negative history ID");}
        return new EventPosition(events.positionAfterHistoryId(historyId),historyId);
    }

    private record EventPosition(long sequence,int initialHistoryId){}

    private static void writeSse(OutputStream out,String id,String event,Object data)throws IOException{
        StringBuilder v=new StringBuilder();if(id!=null)v.append("id: ").append(id).append('\n');if(event!=null)v.append("event: ").append(event).append('\n');
        for(String line:Json.stringify(data).split("\\r?\\n",-1))v.append("data: ").append(line).append('\n');v.append('\n');out.write(v.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static int integer(QueryParameters p,String name,int fallback,int min,int max){String v=p.first(name).orElse(null);if(v==null)return fallback;try{int n=Integer.parseInt(v.trim());if(n<min||n>max)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw ApiException.badRequest("invalid_integer",name+" must be between "+min+" and "+max);}}
    private static void rejectUnknown(QueryParameters p,Set<String> allowed){Set<String> keys=new HashSet<>(p.asMap().keySet());keys.removeAll(allowed);if(!keys.isEmpty())throw ApiException.badRequest("unknown_parameter","Unknown query parameter(s): "+String.join(", ",keys));}
    private void sendApiException(HttpExchange exchange,ApiException e){try{HttpResponses.json(exchange,e.status(),e.responseBody(),e.headers());}catch(IOException x){logging.logToError("Unable to send REST API error response",x);safeClose(exchange);}}
    private static void safeClose(HttpExchange exchange){try{exchange.close();}catch(RuntimeException ignored){}}
    @FunctionalInterface private interface ExchangeHandler{void handle(HttpExchange exchange)throws IOException;}
    private static final class DaemonThreadFactory implements ThreadFactory{
        private final AtomicInteger sequence=new AtomicInteger();public Thread newThread(Runnable r){Thread t=new Thread(r,"burp-history-rest-"+sequence.incrementAndGet());t.setDaemon(true);t.setUncaughtExceptionHandler((x,e)->e.printStackTrace(System.err));return t;}
    }
}
