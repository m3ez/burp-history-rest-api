package burp.api.montoya.proxy.http;
public interface ProxyResponseHandler {
    ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse interceptedResponse);
    ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse interceptedResponse);
}
