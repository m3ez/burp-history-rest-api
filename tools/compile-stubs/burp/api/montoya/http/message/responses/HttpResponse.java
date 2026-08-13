package burp.api.montoya.http.message.responses;
import burp.api.montoya.http.message.HttpMessage;
public interface HttpResponse extends HttpMessage {
    short statusCode();
    String reasonPhrase();
}
