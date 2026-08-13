/* Author: Supakiad S. (m3ez) - E-CQURITY (Thailand) | http://x.com/supakiad_mee */
package com.burphistoryrest.events;

import com.burphistoryrest.server.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** Opaque, instance-bound cursor for the monotonic event sequence. */
public final class EventCursorCodec {
    private EventCursorCodec() { }
    public static String encode(String instanceId, long sequence) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((instanceId + ":" + sequence).getBytes(StandardCharsets.UTF_8));
    }
    public static long decode(String cursor, String instanceId) {
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int split=value.lastIndexOf(':'); if(split<=0)throw new IllegalArgumentException();
            String cursorInstance=value.substring(0,split); long sequence=Long.parseLong(value.substring(split+1));
            if(sequence<0)throw new IllegalArgumentException();
            if(!cursorInstance.equals(instanceId))throw ApiException.conflict("event_cursor_instance_mismatch",
                    "The event cursor belongs to another extension instance", Map.of("currentInstanceId",instanceId));
            return sequence;
        } catch(ApiException e){throw e;} catch(RuntimeException e){throw ApiException.badRequest("invalid_event_cursor","The event cursor is invalid");}
    }
}
