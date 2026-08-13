package burp.api.montoya.proxy.http;
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
public interface InterceptedResponse extends HttpResponse {
    HttpRequest initiatingRequest();
    int messageId();
    String listenerInterface();
    MimeType mimeType();
}
