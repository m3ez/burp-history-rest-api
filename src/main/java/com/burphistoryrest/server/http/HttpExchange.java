/* Author: Supakiad S. (m3ez) - E-CQURITY (Thailand) | http://x.com/supakiad_mee */
package com.burphistoryrest.server.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One request/response exchange handled over a close-delimited HTTP/1.1 connection. */
public final class HttpExchange implements AutoCloseable {
    private final Socket socket;
    private final OutputStream output;
    private final String method;
    private final URI requestUri;
    private final HttpHeaders requestHeaders;
    private final byte[] requestBody;
    private final String requestId = UUID.randomUUID().toString();
    private final long startedNanos = System.nanoTime();
    private volatile boolean responded;
    private volatile int responseStatus;
    private volatile long responseBytes;

    HttpExchange(Socket socket, String method, URI requestUri, HttpHeaders requestHeaders, byte[] requestBody) throws IOException {
        this.socket = socket; this.output = socket.getOutputStream(); this.method = method; this.requestUri = requestUri;
        this.requestHeaders = requestHeaders; this.requestBody = requestBody == null ? new byte[0] : requestBody.clone();
    }

    public String requestMethod() { return method; }
    public URI requestUri() { return requestUri; }
    public HttpHeaders requestHeaders() { return requestHeaders; }
    public byte[] requestBody() { return requestBody.clone(); }
    public String requestBodyUtf8() { return new String(requestBody, StandardCharsets.UTF_8); }
    public String requestId() { return requestId; }
    public String remoteAddress() { return socket.getRemoteSocketAddress() == null ? "unknown" : socket.getRemoteSocketAddress().toString(); }
    public int responseStatus() { return responseStatus; }
    public long responseBytes() { return responseBytes; }
    public long durationMs() { return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L); }

    public synchronized void send(int status, String contentType, byte[] body, Map<String, String> additionalHeaders) throws IOException {
        if (responded) return;
        responded = true; responseStatus = status;
        byte[] safeBody = body == null ? new byte[0] : body; responseBytes = safeBody.length;
        Map<String, String> headers = baseHeaders(contentType, additionalHeaders);
        headers.put("Connection", "close"); headers.put("Content-Length", Integer.toString(safeBody.length));
        writeHead(status, headers);
        if (!method.equalsIgnoreCase("HEAD")) output.write(safeBody);
        output.flush(); close();
    }

    public synchronized OutputStream startStream(int status, String contentType, Map<String, String> additionalHeaders) throws IOException {
        if (responded) throw new IllegalStateException("HTTP response has already started");
        responded = true; responseStatus = status;
        Map<String, String> headers = baseHeaders(contentType, additionalHeaders);
        headers.put("Connection", "close"); writeHead(status, headers); output.flush();
        return new CountingOutputStream(output);
    }

    public synchronized void flush() throws IOException { output.flush(); }
    public boolean responded() { return responded; }
    @Override public void close() { try { socket.close(); } catch (IOException ignored) { } }

    private Map<String, String> baseHeaders(String contentType, Map<String, String> additionalHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(java.time.ZoneOffset.UTC)));
        headers.put("Server", "Burp-History-REST"); headers.put("Content-Type", contentType);
        headers.put("X-Request-Id", requestId); headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Referrer-Policy", "no-referrer"); headers.put("X-Frame-Options", "DENY");
        headers.put("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        additionalHeaders.forEach((name, value) -> { validateHeader(name, value); headers.put(name, value); });
        return headers;
    }

    private void writeHead(int status, Map<String, String> headers) throws IOException {
        StringBuilder head = new StringBuilder(512).append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n");
        headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        head.append("\r\n"); output.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void validateHeader(String name, String value) {
        if (name == null || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value == null
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new IllegalArgumentException("Invalid HTTP response header");
    }

    private static String reasonPhrase(int status) {
        return switch (status) {
            case 200 -> "OK"; case 204 -> "No Content"; case 302 -> "Found"; case 400 -> "Bad Request";
            case 401 -> "Unauthorized"; case 403 -> "Forbidden"; case 404 -> "Not Found";
            case 405 -> "Method Not Allowed"; case 408 -> "Request Timeout"; case 409 -> "Conflict";
            case 413 -> "Payload Too Large"; case 415 -> "Unsupported Media Type"; case 422 -> "Unprocessable Content";
            case 429 -> "Too Many Requests"; case 500 -> "Internal Server Error"; case 501 -> "Not Implemented";
            case 503 -> "Service Unavailable"; default -> status >= 400 ? "Error" : "OK";
        };
    }

    private final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private CountingOutputStream(OutputStream delegate) { this.delegate = delegate; }
        @Override public void write(int b) throws IOException { delegate.write(b); responseBytes++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { delegate.write(b, off, len); responseBytes += len; }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
