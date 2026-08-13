package burp.api.montoya.http.message;
import burp.api.montoya.core.ByteArray;
import java.util.regex.Pattern;
public interface HttpMessage {
    String httpVersion();
    boolean contains(String searchTerm, boolean caseSensitive);
    boolean contains(Pattern pattern);
    ByteArray toByteArray();
}
