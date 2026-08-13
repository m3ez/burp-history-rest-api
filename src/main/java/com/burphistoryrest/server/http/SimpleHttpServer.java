/* Author: Supakiad S. (m3ez) - E-CQURITY (Thailand) | http://x.com/supakiad_mee */
package com.burphistoryrest.server.http;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Hardened dependency-free HTTP/1.1 server. One request is accepted per connection. */
public final class SimpleHttpServer implements AutoCloseable {
    private static final int MAX_REQUEST_LINE = 8_192;
    private static final int MAX_HEADER_LINE = 16_384;
    private static final int MAX_HEADER_BYTES = 65_536;
    private static final int MAX_HEADER_COUNT = 200;
    private static final int SOCKET_TIMEOUT_MS = 15_000;
    private static final String TOKEN = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";

    private final InetSocketAddress address;
    private final int backlog;
    private final Handler handler;
    private final ThreadFactory threadFactory;
    private final int maxRequestBodyBytes;
    private final int workerThreads;
    private final boolean allowAllInterfaces;
    private final AtomicBoolean running = new AtomicBoolean();
    private ServerSocket serverSocket;
    private ThreadPoolExecutor workers;
    private Thread acceptThread;

    public SimpleHttpServer(InetSocketAddress address, int backlog, Handler handler, ThreadFactory threadFactory,
                            int maxRequestBodyBytes, int workerThreads, boolean allowAllInterfaces) {
        this.address = address; this.backlog = backlog; this.handler = handler; this.threadFactory = threadFactory;
        this.maxRequestBodyBytes = maxRequestBodyBytes; this.workerThreads = workerThreads;
        this.allowAllInterfaces = allowAllInterfaces;
    }

    public synchronized void start() throws IOException {
        if (running.get()) return;
        if (address.getAddress() == null) throw new IOException("REST API bind address could not be resolved");
        boolean wildcard = address.getAddress().isAnyLocalAddress();
        if (wildcard && !allowAllInterfaces) {
            throw new IOException("Wildcard binding requires explicit 'Allow all interfaces' opt-in");
        }
        if (!wildcard && allowAllInterfaces) {
            throw new IOException("All-interface mode requires a wildcard socket address");
        }
        ServerSocket created = new ServerSocket(); created.setReuseAddress(true); created.bind(address, backlog);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(workerThreads, workerThreads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workerThreads * 16), threadFactory, new ThreadPoolExecutor.AbortPolicy());
        serverSocket = created; workers = pool; running.set(true);
        acceptThread = threadFactory.newThread(this::acceptLoop); acceptThread.setName("burp-history-rest-accept"); acceptThread.start();
    }

    public synchronized void stop() {
        running.set(false); ServerSocket current = serverSocket; serverSocket = null;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
        ThreadPoolExecutor pool = workers; workers = null; if (pool != null) pool.shutdownNow();
        Thread accept = acceptThread; acceptThread = null; if (accept != null) accept.interrupt();
    }
    @Override public void close() { stop(); }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept(); socket.setSoTimeout(SOCKET_TIMEOUT_MS); socket.setTcpNoDelay(true);
                ThreadPoolExecutor pool = workers;
                if (pool == null) { socket.close(); return; }
                try { pool.execute(() -> handleConnection(socket)); }
                catch (RuntimeException rejected) { sendBareError(socket, 503, "Server is busy"); }
            } catch (SocketException e) { if (running.get()) e.printStackTrace(System.err); }
            catch (IOException e) { if (running.get()) e.printStackTrace(System.err); }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            String requestLine = readLine(input, MAX_REQUEST_LINE);
            if (requestLine == null || requestLine.isBlank()) { socket.close(); return; }
            String[] parts = requestLine.split(" ", -1);
            if (parts.length != 3 || !parts[0].matches(TOKEN)
                    || !(parts[2].equals("HTTP/1.1") || parts[2].equals("HTTP/1.0"))) {
                sendBareError(socket, 400, "Malformed or unsupported HTTP request line"); return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT); URI uri = parseTarget(parts[1]);
            HttpHeaders headers = readHeaders(input);
            if (parts[2].equals("HTTP/1.1") && headers.all("Host").size() != 1) {
                sendBareError(socket, 400, "HTTP/1.1 requires exactly one Host header"); return;
            }
            List<String> transferEncodings = headers.all("Transfer-Encoding");
            List<String> lengths = headers.all("Content-Length");
            if (!transferEncodings.isEmpty() && !lengths.isEmpty()) {
                sendBareError(socket, 400, "Transfer-Encoding and Content-Length must not be combined"); return;
            }
            byte[] body;
            if (!transferEncodings.isEmpty()) {
                if (transferEncodings.size() != 1 || !transferEncodings.getFirst().trim().equalsIgnoreCase("chunked")) {
                    sendBareError(socket, 501, "Only chunked Transfer-Encoding is supported"); return;
                }
                body = readChunked(input, maxRequestBodyBytes);
            } else {
                int contentLength = parseContentLength(lengths);
                if (contentLength > maxRequestBodyBytes) { sendBareError(socket, 413, "HTTP request body exceeds the configured maximum"); return; }
                body = readExactly(input, contentLength);
            }
            HttpExchange exchange = new HttpExchange(socket, method, uri, headers, body);
            handler.handle(exchange); if (!exchange.responded()) exchange.close();
        } catch (java.net.SocketTimeoutException e) { sendBareError(socket, 408, "HTTP request timed out"); }
        catch (PayloadTooLargeException e) { sendBareError(socket, 413, e.getMessage()); }
        catch (IllegalArgumentException e) { sendBareError(socket, 400, e.getMessage() == null ? "Bad request" : e.getMessage()); }
        catch (IOException e) { try { socket.close(); } catch (IOException ignored) { } }
        catch (RuntimeException e) { e.printStackTrace(System.err); sendBareError(socket, 500, "Internal server error"); }
    }

    private static HttpHeaders readHeaders(BufferedInputStream input) throws IOException {
        HttpHeaders headers = new HttpHeaders(); int bytes = 0, count = 0;
        while (true) {
            String line = readLine(input, MAX_HEADER_LINE);
            if (line == null) throw new IllegalArgumentException("Unexpected end of headers");
            bytes += line.length() + 2;
            if (bytes > MAX_HEADER_BYTES || ++count > MAX_HEADER_COUNT) throw new IllegalArgumentException("HTTP headers are too large");
            if (line.isEmpty()) return headers;
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') throw new IllegalArgumentException("Obsolete folded headers are not supported");
            int separator = line.indexOf(':');
            if (separator <= 0) throw new IllegalArgumentException("Malformed HTTP header");
            String name = line.substring(0, separator);
            if (!name.matches(TOKEN)) throw new IllegalArgumentException("Invalid HTTP header name");
            String value = line.substring(separator + 1).trim();
            if (containsControl(value)) throw new IllegalArgumentException("Invalid control character in HTTP header");
            headers.add(name, value);
        }
    }

    private static int parseContentLength(List<String> values) {
        if (values.isEmpty()) return 0;
        Set<String> normalized = new HashSet<>();
        for (String header : values) for (String part : header.split(",")) normalized.add(part.trim());
        if (normalized.size() != 1) throw new IllegalArgumentException("Conflicting Content-Length headers");
        String value = normalized.iterator().next();
        if (!value.matches("[0-9]+") || value.length() > 10) throw new IllegalArgumentException("Content-Length must be a non-negative integer");
        try { long length = Long.parseLong(value); if (length > Integer.MAX_VALUE) throw new NumberFormatException(); return (int) length; }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Content-Length is too large"); }
    }

    private static byte[] readChunked(BufferedInputStream input, int maximum) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(Math.min(maximum, 8192));
        while (true) {
            String line = readLine(input, 128); if (line == null) throw new IllegalArgumentException("Missing chunk size");
            String sizeText = line.split(";", 2)[0].trim();
            if (!sizeText.matches("[0-9A-Fa-f]{1,8}")) throw new IllegalArgumentException("Invalid chunk size");
            int size;
            try { size = Integer.parseUnsignedInt(sizeText, 16); } catch (NumberFormatException e) { throw new IllegalArgumentException("Chunk size is too large"); }
            if (size == 0) { readTrailers(input); return result.toByteArray(); }
            if ((long) result.size() + size > maximum) throw new PayloadTooLargeException();
            result.write(readExactly(input, size));
            String ending = readLine(input, 2); if (ending == null || !ending.isEmpty()) throw new IllegalArgumentException("Chunk data is not followed by CRLF");
        }
    }

    private static void readTrailers(BufferedInputStream input) throws IOException {
        int count = 0, bytes = 0;
        while (true) {
            String line = readLine(input, MAX_HEADER_LINE); if (line == null) throw new IllegalArgumentException("Unexpected end of chunked trailers");
            bytes += line.length() + 2; if (bytes > 16_384 || ++count > 50) throw new IllegalArgumentException("Chunked trailers are too large");
            if (line.isEmpty()) return;
            int separator = line.indexOf(':'); if (separator <= 0 || !line.substring(0, separator).matches(TOKEN)) throw new IllegalArgumentException("Malformed trailer header");
        }
    }

    private static byte[] readExactly(BufferedInputStream input, int length) throws IOException {
        byte[] result = new byte[length]; int offset = 0;
        while (offset < length) { int read = input.read(result, offset, length - offset); if (read < 0) throw new IllegalArgumentException("Unexpected end of HTTP request body"); offset += read; }
        return result;
    }

    private static URI parseTarget(String target) {
        if (target.isEmpty() || target.length() > MAX_REQUEST_LINE || containsControl(target)) throw new IllegalArgumentException("Invalid request target");
        try {
            URI parsed = new URI(target);
            if (parsed.isAbsolute()) { String rawPath = parsed.getRawPath(); String path = rawPath == null || rawPath.isEmpty() ? "/" : rawPath; return new URI(null, null, path, parsed.getRawQuery(), null); }
            if (!target.startsWith("/")) throw new IllegalArgumentException("Only origin-form request targets are supported");
            return parsed;
        } catch (URISyntaxException e) { throw new IllegalArgumentException("Invalid request target", e); }
    }

    private static boolean containsControl(String value) { for (int i = 0; i < value.length(); i++) { char c = value.charAt(i); if (c < 0x20 && c != '\t' || c == 0x7f) return true; } return false; }

    private static String readLine(BufferedInputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(256, maximumBytes)); boolean sawCr = false;
        while (buffer.size() <= maximumBytes) {
            int value = input.read(); if (value == -1) return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.ISO_8859_1);
            if (sawCr) { if (value == '\n') return buffer.toString(StandardCharsets.ISO_8859_1); buffer.write('\r'); sawCr = false; }
            if (value == '\r') sawCr = true; else if (value == '\n') return buffer.toString(StandardCharsets.ISO_8859_1); else buffer.write(value);
        }
        throw new IllegalArgumentException("HTTP line exceeds configured maximum");
    }

    private static void sendBareError(Socket socket, int status, String message) {
        try (socket) {
            if (message == null) message = "Bad request";
            byte[] body = ("{\"error\":{\"code\":\"bad_http_request\",\"message\":\"" + jsonEscape(message) + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            String reason = switch (status) { case 408 -> "Request Timeout"; case 413 -> "Payload Too Large"; case 500 -> "Internal Server Error"; case 501 -> "Not Implemented"; case 503 -> "Service Unavailable"; default -> "Bad Request"; };
            String head = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: application/json; charset=UTF-8\r\nX-Content-Type-Options: nosniff\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            OutputStream output = socket.getOutputStream(); output.write(head.getBytes(StandardCharsets.ISO_8859_1)); output.write(body); output.flush();
        } catch (IOException ignored) { }
    }

    private static String jsonEscape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " "); }
    @FunctionalInterface public interface Handler { void handle(HttpExchange exchange) throws IOException; }
    private static final class PayloadTooLargeException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private PayloadTooLargeException() { super("HTTP request body exceeds the configured maximum"); }
    }
}
