/* Author: Supakiad S. (m3ez) - E-CQURITY (Thailand) | http://x.com/supakiad_mee */
package com.burphistoryrest.events;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.proxy.http.InterceptedResponse;
import burp.api.montoya.proxy.http.ProxyResponseHandler;
import burp.api.montoya.proxy.http.ProxyResponseReceivedAction;
import burp.api.montoya.proxy.http.ProxyResponseToBeSentAction;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Non-modifying Montoya handler that publishes completed proxy responses to the SSE ring buffer. */
public final class ProxyEventCapture implements ProxyResponseHandler {
    private final HistoryEventBroker broker;
    private final Logging logging;

    public ProxyEventCapture(HistoryEventBroker broker, Logging logging) { this.broker = broker; this.logging = logging; }

    @Override public ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse response) {
        return ProxyResponseReceivedAction.continueWith(response);
    }

    @Override public ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse response) {
        try {
            HttpRequest request = response.initiatingRequest();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", response.messageId()); item.put("time", Instant.now().toString());
            if (request != null) {
                item.put("method", safe(request.method())); item.put("url", safe(request.url()));
                item.put("path", safe(request.path()));
                if (request.httpService() != null) {
                    item.put("host", safe(request.httpService().host())); item.put("port", request.httpService().port());
                    item.put("secure", request.httpService().secure());
                }
            }
            item.put("status", (int) response.statusCode()); item.put("reason", safe(response.reasonPhrase()));
            item.put("mime", response.mimeType() == null ? null : response.mimeType().toString());
            item.put("listener", safe(response.listenerInterface()));
            broker.publish(response.messageId(), item);
        } catch (RuntimeException e) {
            logging.logToError("Unable to publish Proxy history event", e);
        }
        return ProxyResponseToBeSentAction.continueWith(response);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
