package burp.api.montoya.proxy;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.time.ZonedDateTime;
import java.util.regex.Pattern;
public interface ProxyHttpRequestResponse {
    HttpRequest finalRequest();
    HttpResponse response();
    HttpService httpService();
    boolean edited();
    ZonedDateTime time();
    int listenerPort();
    int id();
    MimeType mimeType();
    boolean hasResponse();
    boolean contains(String searchTerm, boolean caseSensitive);
    boolean contains(Pattern pattern);
}
