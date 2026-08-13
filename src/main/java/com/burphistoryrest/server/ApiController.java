/* Author: Supakiad S. (m3ez) - E-CQURITY (Thailand) | http://x.com/supakiad_mee */
package com.burphistoryrest.server;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;
import burp.api.montoya.logging.Logging;
import com.burphistoryrest.config.ApiSettings;
import com.burphistoryrest.events.HistoryEventBroker;
import com.burphistoryrest.events.ProxyEventCapture;
import com.burphistoryrest.history.HistoryService;
import com.burphistoryrest.security.AccessTokenStore;
import com.burphistoryrest.telemetry.AuditLogger;
import com.burphistoryrest.telemetry.MetricsRegistry;

import java.io.IOException;
import java.util.UUID;

public final class ApiController implements AutoCloseable {
    private final MontoyaApi api;
    private final Logging logging;
    private final AccessTokenStore tokens;
    private final String instanceId = UUID.randomUUID().toString();
    private final MetricsRegistry metrics = new MetricsRegistry();
    private ApiServer server;
    private Registration proxyRegistration;
    private AuditLogger audit;
    private ApiSettings settings;
    private String lastError;

    public ApiController(MontoyaApi api, AccessTokenStore tokens) { this.api = api; this.logging = api.logging(); this.tokens = tokens; }

    public synchronized void start(ApiSettings newSettings) {
        if (server != null) return;
        HistoryEventBroker broker = new HistoryEventBroker(instanceId, newSettings.eventBufferSize(), metrics);
        try {
            proxyRegistration = api.proxy().registerResponseHandler(new ProxyEventCapture(broker, logging));
            HistoryService history = new HistoryService(api, newSettings, instanceId, metrics);
            audit = new AuditLogger(newSettings, logging, metrics);
            ApiServer created = new ApiServer(newSettings, history, tokens, broker, metrics, audit, logging);
            created.start(); server = created; settings = newSettings; lastError = null;
            logging.logToOutput("Burp History REST API listening on " + newSettings.listenerDescription()
                    + "; local dashboard: " + newSettings.baseUrl());
        } catch (IOException | RuntimeException e) {
            cleanupPartial(); lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            logging.logToError("Unable to start Burp History REST API", e);
            throw new IllegalStateException("Unable to start REST API: " + lastError, e);
        }
    }

    public synchronized void restart(ApiSettings newSettings) { stop(); start(newSettings); }

    public synchronized void stop() {
        ApiServer current = server; server = null; if (current != null) current.stop();
        Registration registration = proxyRegistration; proxyRegistration = null;
        if (registration != null) try { registration.deregister(); } catch (RuntimeException e) { logging.logToError("Unable to deregister Proxy event handler", e); }
        AuditLogger logger = audit; audit = null; if (logger != null) logger.close();
        if (current != null) logging.logToOutput("Burp History REST API stopped");
    }

    private void cleanupPartial() {
        try { if (server != null) server.stop(); } catch (RuntimeException ignored) { } server = null;
        try { if (proxyRegistration != null) proxyRegistration.deregister(); } catch (RuntimeException ignored) { } proxyRegistration = null;
        if (audit != null) audit.close(); audit = null;
    }

    public synchronized Status status() {
        return new Status(
                server != null,
                settings == null ? null : settings.baseUrl(),
                settings == null ? null : settings.listenerDescription(),
                settings != null && settings.networkExposed(),
                lastError,
                tokens.list().size()
        );
    }
    @Override public void close() { stop(); }
    public record Status(
            boolean running,
            String baseUrl,
            String listenerDescription,
            boolean networkExposed,
            String lastError,
            int tokenCount
    ) { }
}
