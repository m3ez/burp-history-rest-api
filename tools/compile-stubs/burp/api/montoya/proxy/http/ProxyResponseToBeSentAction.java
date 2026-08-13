package burp.api.montoya.proxy.http;
import burp.api.montoya.http.message.responses.HttpResponse;
public interface ProxyResponseToBeSentAction {
    static ProxyResponseToBeSentAction continueWith(HttpResponse response) { return null; }
}
