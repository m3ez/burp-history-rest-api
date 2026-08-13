package burp.api.montoya.http;
public interface HttpService {
    String host();
    int port();
    boolean secure();
}
