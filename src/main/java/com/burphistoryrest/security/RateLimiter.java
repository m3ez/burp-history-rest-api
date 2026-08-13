/* Author: Supakiad S. (m3ez) - E-CQURITY (Thailand) | http://x.com/supakiad_mee */
package com.burphistoryrest.security;

import java.util.concurrent.ConcurrentHashMap;

/** Per-token fixed-window limiter with bounded state. */
public final class RateLimiter {
    private final int requestsPerMinute;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }

    public Decision allow(String principalId) {
        long minute = System.currentTimeMillis() / 60_000L;
        Window window = windows.compute(principalId, (ignored, current) -> {
            if (current == null || current.minute != minute) return new Window(minute, 1);
            return new Window(minute, current.count + 1);
        });
        if (windows.size() > 256 && (minute & 7) == 0) windows.entrySet().removeIf(e -> e.getValue().minute < minute - 2);
        boolean allowed = window.count <= requestsPerMinute;
        int remaining = Math.max(0, requestsPerMinute - window.count);
        int retryAfter = (int) Math.max(1, 60 - (System.currentTimeMillis() / 1000L) % 60);
        return new Decision(allowed, remaining, retryAfter);
    }

    private record Window(long minute, int count) { }
    public record Decision(boolean allowed, int remaining, int retryAfterSeconds) { }
}
