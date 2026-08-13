/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.config;

import burp.api.montoya.logging.Logging;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Preferences;

/** Stores active settings in the Burp project, with one-time fallback to legacy user preferences. */
public final class SettingsStore {
    private static final String P = "burp_history_rest.";
    private final Preferences preferences;
    private final PersistedObject project;
    private final Logging logging;

    public SettingsStore(Preferences preferences, PersistedObject project, Logging logging) {
        this.preferences = preferences;
        this.project = project;
        this.logging = logging;
    }

    public ApiSettings load() {
        ApiSettings d = ApiSettings.defaults();
        try {
            ApiSettings s = new ApiSettings(
                    string("bind_address", d.bindAddress()), bool("allow_all_interfaces", d.allowAllInterfaces()),
                    integer("port", d.port()), integer("max_page_size", d.maxPageSize()),
                    integer("max_message_mib", d.maxMessageMiB()), integer("max_request_body_kib", d.maxRequestBodyKiB()),
                    integer("max_scan_items", d.maxScanItems()), integer("query_timeout_ms", d.queryTimeoutMs()),
                    integer("worker_threads", d.workerThreads()), integer("max_concurrent_queries", d.maxConcurrentQueries()),
                    integer("max_concurrent_events", d.maxConcurrentEvents()), integer("event_buffer_size", d.eventBufferSize()),
                    integer("rate_limit_per_minute", d.rateLimitPerMinute()),
                    bool("raw_access_enabled", d.rawAccessEnabled()), bool("audit_enabled", d.auditEnabled()),
                    string("audit_log_path", d.auditLogPath()), integer("audit_max_mib", d.auditMaxMiB()),
                    integer("audit_retained_files", d.auditRetainedFiles()), bool("auto_start", d.autoStart()),
                    bool("regex_enabled", d.regexEnabled()), bool("redaction_enabled", d.redactionEnabled()),
                    string("redacted_header_names", d.redactedHeaderNames()),
                    string("redacted_parameter_names", d.redactedParameterNames()),
                    string("redaction_replacement", d.redactionReplacement())
            );
            save(s);
            return s;
        } catch (RuntimeException e) {
            logging.logToError("Unable to load project REST API settings; safe defaults will be used", e);
            try { save(d); } catch (RuntimeException ignored) { }
            return d;
        }
    }

    public void save(ApiSettings s) {
        setString("bind_address", s.bindAddress()); setBoolean("allow_all_interfaces", s.allowAllInterfaces());
        setInteger("port", s.port()); setInteger("max_page_size", s.maxPageSize());
        setInteger("max_message_mib", s.maxMessageMiB()); setInteger("max_request_body_kib", s.maxRequestBodyKiB());
        setInteger("max_scan_items", s.maxScanItems()); setInteger("query_timeout_ms", s.queryTimeoutMs());
        setInteger("worker_threads", s.workerThreads()); setInteger("max_concurrent_queries", s.maxConcurrentQueries());
        setInteger("max_concurrent_events", s.maxConcurrentEvents()); setInteger("event_buffer_size", s.eventBufferSize());
        setInteger("rate_limit_per_minute", s.rateLimitPerMinute());
        setBoolean("raw_access_enabled", s.rawAccessEnabled()); setBoolean("audit_enabled", s.auditEnabled());
        setString("audit_log_path", s.auditLogPath()); setInteger("audit_max_mib", s.auditMaxMiB());
        setInteger("audit_retained_files", s.auditRetainedFiles()); setBoolean("auto_start", s.autoStart());
        setBoolean("regex_enabled", s.regexEnabled()); setBoolean("redaction_enabled", s.redactionEnabled());
        setString("redacted_header_names", s.redactedHeaderNames());
        setString("redacted_parameter_names", s.redactedParameterNames());
        setString("redaction_replacement", s.redactionReplacement());
    }

    private int integer(String key, int fallback) {
        Integer v = project == null ? null : project.getInteger(P + key);
        if (v == null && preferences != null) v = preferences.getInteger(P + key);
        return v == null ? fallback : v;
    }
    private boolean bool(String key, boolean fallback) {
        Boolean v = project == null ? null : project.getBoolean(P + key);
        if (v == null && preferences != null) v = preferences.getBoolean(P + key);
        return v == null ? fallback : v;
    }
    private String string(String key, String fallback) {
        String v = project == null ? null : project.getString(P + key);
        if ((v == null || v.isBlank()) && preferences != null) v = preferences.getString(P + key);
        return v == null || v.isBlank() ? fallback : v;
    }
    private void setInteger(String key, int value) {
        if (project != null) project.setInteger(P + key, value); else if (preferences != null) preferences.setInteger(P + key, value);
    }
    private void setBoolean(String key, boolean value) {
        if (project != null) project.setBoolean(P + key, value); else if (preferences != null) preferences.setBoolean(P + key, value);
    }
    private void setString(String key, String value) {
        if (project != null) project.setString(P + key, value); else if (preferences != null) preferences.setString(P + key, value);
    }
}
