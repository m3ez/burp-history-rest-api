package burp.api.montoya.ui;
import burp.api.montoya.core.Registration;
import java.awt.Component;
public interface UserInterface {
    Registration registerSuiteTab(String title, Component component);
    void applyThemeToComponent(Component component);
}
