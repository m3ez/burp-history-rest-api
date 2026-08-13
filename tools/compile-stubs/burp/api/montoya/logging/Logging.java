package burp.api.montoya.logging;
public interface Logging {
    void logToOutput(String message);
    void logToOutput(Object object);
    void logToError(String message);
    void logToError(String message, Throwable cause);
    void logToError(Throwable cause);
}
