/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.history;

import com.burphistoryrest.server.ApiException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Encodes a Burp-process instance ID and exclusive history ID into an opaque sync cursor. */
public final class CursorCodec {
    private CursorCodec() {
    }

    public static String encode(String instanceId, int afterId) {
        String value = instanceId + ":" + afterId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String encoded, String expectedInstanceId) {
        if (encoded == null || encoded.isBlank() || encoded.length() > 512) {
            throw ApiException.badRequest("invalid_cursor", "cursor is missing or too long");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf(':');
            if (separator <= 0 || separator == decoded.length() - 1) {
                throw new IllegalArgumentException("invalid cursor structure");
            }
            String instanceId = decoded.substring(0, separator);
            int afterId = Integer.parseInt(decoded.substring(separator + 1));
            if (afterId < 0) {
                throw new IllegalArgumentException("negative history ID");
            }
            if (!expectedInstanceId.equals(instanceId)) {
                throw ApiException.conflict(
                        "cursor_instance_mismatch",
                        "The cursor belongs to a different Burp extension instance; start a new synchronization",
                        java.util.Map.of("expectedInstanceId", expectedInstanceId, "cursorInstanceId", instanceId)
                );
            }
            return new Cursor(instanceId, afterId);
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw ApiException.badRequest("invalid_cursor", "cursor is not a valid Burp History REST cursor");
        }
    }

    public record Cursor(String instanceId, int afterId) {
    }
}
