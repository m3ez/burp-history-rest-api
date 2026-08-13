/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.config;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/** Validated, project-scoped runtime settings. */
public record ApiSettings(
        String bindAddress,
        boolean allowAllInterfaces,
        int port,
        int maxPageSize,
        int maxMessageMiB,
        int maxRequestBodyKiB,
        int maxScanItems,
        int queryTimeoutMs,
        int workerThreads,
        int maxConcurrentQueries,
        int maxConcurrentEvents,
        int eventBufferSize,
        int rateLimitPerMinute,
        boolean rawAccessEnabled,
        boolean auditEnabled,
        String auditLogPath,
        int auditMaxMiB,
        int auditRetainedFiles,
        boolean autoStart,
        boolean regexEnabled,
        boolean redactionEnabled,
        String redactedHeaderNames,
        String redactedParameterNames,
        String redactionReplacement
) {
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    public static final int DEFAULT_PORT = 8090;
    public static final int DEFAULT_MAX_PAGE_SIZE = 500;
    public static final int DEFAULT_MAX_MESSAGE_MIB = 10;
    public static final int DEFAULT_MAX_REQUEST_BODY_KIB = 256;
    public static final int DEFAULT_MAX_SCAN_ITEMS = 500_000;
    public static final int DEFAULT_QUERY_TIMEOUT_MS = 15_000;
    public static final int DEFAULT_WORKER_THREADS = 12;
    public static final int DEFAULT_MAX_CONCURRENT_QUERIES = 8;
    public static final int DEFAULT_MAX_CONCURRENT_EVENTS = 16;
    public static final int DEFAULT_EVENT_BUFFER_SIZE = 20_000;
    public static final int DEFAULT_RATE_LIMIT_PER_MINUTE = 1_200;
    public static final int DEFAULT_AUDIT_MAX_MIB = 20;
    public static final int DEFAULT_AUDIT_RETAINED_FILES = 5;
    public static final String DEFAULT_REDACTED_HEADERS =
            "Authorization,Proxy-Authorization,Cookie,Set-Cookie,X-API-Key,X-Auth-Token";
    public static final String DEFAULT_REDACTED_PARAMETERS =
            "password,passwd,pwd,token,access_token,refresh_token,api_key,apikey,secret,client_secret,session,sessionid";
    public static final String DEFAULT_REDACTION_REPLACEMENT = "[REDACTED]";
    private static final SecureRandom RANDOM = new SecureRandom();

    public ApiSettings {
        bindAddress = NetworkInterfaceCatalog.normalizeConfiguredAddress(bindAddress);
        range("Port", port, 1, 65_535);
        range("Maximum page size", maxPageSize, 1, 5_000);
        range("Maximum message size", maxMessageMiB, 1, 100);
        range("Maximum API request body", maxRequestBodyKiB, 1, 4_096);
        range("Maximum scanned history items", maxScanItems, 1_000, 2_000_000);
        range("Query timeout", queryTimeoutMs, 250, 120_000);
        range("Worker threads", workerThreads, 2, 128);
        range("Concurrent queries", maxConcurrentQueries, 1, 64);
        range("Concurrent event streams", maxConcurrentEvents, 1, 128);
        range("Event buffer size", eventBufferSize, 100, 1_000_000);
        range("Rate limit", rateLimitPerMinute, 60, 100_000);
        range("Audit log size", auditMaxMiB, 1, 1_024);
        range("Audit retained files", auditRetainedFiles, 1, 100);
        auditLogPath = validatePath(auditLogPath);
        redactedHeaderNames = validateList("Redacted header names", redactedHeaderNames);
        redactedParameterNames = validateList("Redacted parameter names", redactedParameterNames);
        if (redactionReplacement == null || redactionReplacement.isEmpty() || redactionReplacement.length() > 128
                || redactionReplacement.indexOf('\r') >= 0 || redactionReplacement.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Redaction replacement must contain 1 to 128 characters without newlines");
        }
    }

    public static ApiSettings defaults() {
        return new ApiSettings(
                DEFAULT_BIND_ADDRESS, false,
                DEFAULT_PORT, DEFAULT_MAX_PAGE_SIZE, DEFAULT_MAX_MESSAGE_MIB,
                DEFAULT_MAX_REQUEST_BODY_KIB, DEFAULT_MAX_SCAN_ITEMS, DEFAULT_QUERY_TIMEOUT_MS,
                DEFAULT_WORKER_THREADS, DEFAULT_MAX_CONCURRENT_QUERIES, DEFAULT_MAX_CONCURRENT_EVENTS,
                DEFAULT_EVENT_BUFFER_SIZE, DEFAULT_RATE_LIMIT_PER_MINUTE,
                false, true, defaultAuditPath(), DEFAULT_AUDIT_MAX_MIB, DEFAULT_AUDIT_RETAINED_FILES,
                true, false, true, DEFAULT_REDACTED_HEADERS, DEFAULT_REDACTED_PARAMETERS,
                DEFAULT_REDACTION_REPLACEMENT
        );
    }

    public static String generateToken() {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    public int maxMessageBytes() { return Math.multiplyExact(maxMessageMiB, 1024 * 1024); }
    public int maxRequestBodyBytes() { return Math.multiplyExact(maxRequestBodyKiB, 1024); }
    public long auditMaxBytes() { return Math.multiplyExact((long) auditMaxMiB, 1024L * 1024L); }

    /** Socket address used by the listener. A wildcard address is created only after explicit opt-in. */
    public InetSocketAddress listenSocketAddress() {
        if (allowAllInterfaces) return new InetSocketAddress(port);
        InetAddress address = NetworkInterfaceCatalog.resolveNumericAddress(bindAddress);
        if (!NetworkInterfaceCatalog.isAssignedLocalAddress(address)) {
            throw new IllegalStateException(
                    "Bind address is not assigned to an active local network interface: " + bindAddress
            );
        }
        return new InetSocketAddress(address, port);
    }

    /** Host reported for listener diagnostics. Wildcard binding is represented as an asterisk. */
    public String listenHost() { return allowAllInterfaces ? "*" : bindAddress; }

    /** A connectable local URL used by the dashboard button and generated OpenAPI document. */
    public String baseUrl() {
        return "http://" + urlHost(allowAllInterfaces ? DEFAULT_BIND_ADDRESS : bindAddress) + ":" + port;
    }

    public String listenerDescription() {
        return allowAllInterfaces
                ? "*:" + port + " (all interfaces)"
                : urlHost(bindAddress) + ":" + port;
    }

    public boolean networkExposed() {
        if (allowAllInterfaces) return true;
        InetAddress address = NetworkInterfaceCatalog.resolveNumericAddress(bindAddress);
        return !address.isLoopbackAddress();
    }

    public static String defaultAuditPath() {
        return Path.of(System.getProperty("user.home", "."), ".burp-history-rest", "audit.jsonl").toString();
    }

    @Override public String toString() {
        return "ApiSettings[bindAddress=" + bindAddress + ", allowAllInterfaces=" + allowAllInterfaces
                + ", port=" + port + ", workers=" + workerThreads
                + ", maxConcurrentQueries=" + maxConcurrentQueries
                + ", maxConcurrentEvents=" + maxConcurrentEvents
                + ", rawAccessEnabled=" + rawAccessEnabled
                + ", auditEnabled=" + auditEnabled + "]";
    }

    private static String urlHost(String address) {
        if (address.indexOf(':') < 0) return address;
        return "[" + address.replace("%", "%25") + "]";
    }

    private static void range(String label, int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException(label + " must be between " + min + " and " + max);
    }

    private static String validateList(String label, String value) {
        if (value == null || value.isBlank() || value.length() > 4_096
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(label + " must contain 1 to 4096 characters without newlines");
        }
        return value.trim();
    }

    private static String validatePath(String value) {
        if (value == null || value.isBlank() || value.length() > 2_048
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Audit log path must contain 1 to 2048 safe characters");
        }
        return value.trim();
    }
}
