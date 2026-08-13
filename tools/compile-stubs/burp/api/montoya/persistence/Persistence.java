package burp.api.montoya.persistence;
public interface Persistence {
    Preferences preferences();
    PersistedObject extensionData();
}
