package burp.api.montoya;
import burp.api.montoya.extension.Extension;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.proxy.Proxy;
import burp.api.montoya.ui.UserInterface;
public interface MontoyaApi {
    Extension extension();
    Logging logging();
    Persistence persistence();
    Proxy proxy();
    UserInterface userInterface();
}
