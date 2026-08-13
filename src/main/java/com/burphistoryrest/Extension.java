/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import com.burphistoryrest.config.ApiSettings;
import com.burphistoryrest.config.SettingsStore;
import com.burphistoryrest.security.AccessTokenStore;
import com.burphistoryrest.server.ApiController;
import com.burphistoryrest.ui.SettingsPanel;

import javax.swing.SwingUtilities;

public final class Extension implements BurpExtension {
    private ApiController controller;
    private SettingsPanel settingsPanel;

    @Override public void initialize(MontoyaApi api) {
        api.extension().setName(BuildInfo.NAME);
        PersistedObject projectData = null;
        try { projectData = api.persistence().extensionData(); }
        catch (RuntimeException e) { api.logging().logToError("Project persistence is unavailable; settings will use user preferences", e); }
        SettingsStore settingsStore = new SettingsStore(api.persistence().preferences(), projectData, api.logging());
        ApiSettings settings = settingsStore.load();
        AccessTokenStore tokens = new AccessTokenStore(projectData, api.persistence().preferences(), api.logging());
        controller = new ApiController(api, tokens);
        settingsPanel = new SettingsPanel(api, settingsStore, controller, settings, tokens);
        api.userInterface().applyThemeToComponent(settingsPanel);
        api.userInterface().registerSuiteTab("History REST", settingsPanel);
        api.extension().registerUnloadingHandler(this::unload);
        api.logging().logToOutput(BuildInfo.NAME + " " + BuildInfo.VERSION + " by " + BuildInfo.AUTHOR_DISPLAY
                + " loaded (Montoya API " + BuildInfo.MONTOYA_API_VERSION + ")");
        tokens.bootstrapToken().ifPresent(token -> api.logging().logToOutput(
                "Initial project API token created: id=" + token.id() + " (secret is shown once in the History REST tab)"));
        if (settings.autoStart()) try { controller.start(settings); }
        catch (RuntimeException e) { api.logging().logToError("REST API auto-start failed; open the History REST tab to retry", e); }
    }

    private void unload() {
        SettingsPanel panel = settingsPanel;
        if (panel != null) { if (SwingUtilities.isEventDispatchThread()) panel.close(); else SwingUtilities.invokeLater(panel::close); }
        ApiController current = controller; if (current != null) current.close();
    }
}
