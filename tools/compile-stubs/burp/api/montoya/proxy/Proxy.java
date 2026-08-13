package burp.api.montoya.proxy;
import burp.api.montoya.core.Registration;
import burp.api.montoya.proxy.http.ProxyResponseHandler;
import java.util.List;
public interface Proxy {
    List<ProxyHttpRequestResponse> history();
    List<ProxyHttpRequestResponse> history(ProxyHistoryFilter filter);
    Registration registerResponseHandler(ProxyResponseHandler handler);
}
