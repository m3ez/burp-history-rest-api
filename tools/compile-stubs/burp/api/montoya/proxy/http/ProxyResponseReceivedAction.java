package burp.api.montoya.proxy.http;
import burp.api.montoya.http.message.responses.HttpResponse;
public interface ProxyResponseReceivedAction {
    static ProxyResponseReceivedAction continueWith(HttpResponse response) { return null; }
}
