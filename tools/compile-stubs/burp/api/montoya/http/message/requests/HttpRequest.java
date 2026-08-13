package burp.api.montoya.http.message.requests;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpMessage;
public interface HttpRequest extends HttpMessage {
    boolean isInScope();
    HttpService httpService();
    String url();
    String method();
    String path();
    String query();
    String pathWithoutQuery();
    String fileExtension();
}
