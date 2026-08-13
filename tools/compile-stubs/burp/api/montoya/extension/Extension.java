package burp.api.montoya.extension;
import burp.api.montoya.core.Registration;
public interface Extension {
    void setName(String extensionName);
    Registration registerUnloadingHandler(ExtensionUnloadingHandler handler);
}
