/* Author: Supakiad S. (m3ez) - E-CQURITY (Thailand) | http://x.com/supakiad_mee */
package com.burphistoryrest.ui;

import burp.api.montoya.MontoyaApi;
import com.burphistoryrest.config.ApiSettings;
import com.burphistoryrest.config.NetworkInterfaceCatalog;
import com.burphistoryrest.config.SettingsStore;
import com.burphistoryrest.security.AccessScope;
import com.burphistoryrest.security.AccessTokenStore;
import com.burphistoryrest.server.ApiController;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Burp tab for project-scoped production settings and client token administration. */
@SuppressWarnings("serial")
public final class SettingsPanel extends JPanel {
    private final MontoyaApi api;
    private final SettingsStore store;
    private final ApiController controller;
    private final AccessTokenStore tokens;
    private final JComboBox<NetworkInterfaceCatalog.AddressOption> bindAddress = new JComboBox<>();
    private final JCheckBox allowAllInterfaces;
    private final JButton refreshInterfaces = new JButton("Refresh");
    private final JTextArea bindingNotice = note("");
    private final JSpinner port;
    private final JSpinner maxPage;
    private final JSpinner maxMessage;
    private final JSpinner maxPost;
    private final JSpinner maxScan;
    private final JSpinner queryTimeout;
    private final JSpinner workers;
    private final JSpinner concurrentQueries;
    private final JSpinner concurrentEvents;
    private final JSpinner eventBuffer;
    private final JSpinner rateLimit;
    private final JSpinner auditSize;
    private final JSpinner auditFiles;
    private final JCheckBox rawEnabled;
    private final JCheckBox auditEnabled;
    private final JCheckBox autoStart;
    private final JCheckBox regexEnabled;
    private final JCheckBox redactionEnabled;
    private final JTextField auditPath;
    private final JTextField redactedHeaders;
    private final JTextField redactedParameters;
    private final JTextField replacement;
    private final JTextArea tokenList = new JTextArea(7, 80);
    private final JLabel status = new JLabel();
    private final Timer timer;

    public SettingsPanel(
            MontoyaApi api,
            SettingsStore store,
            ApiController controller,
            ApiSettings settings,
            AccessTokenStore tokens
    ) {
        super(new BorderLayout(8, 8));
        this.api = api;
        this.store = store;
        this.controller = controller;
        this.tokens = tokens;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        allowAllInterfaces = new JCheckBox(
                "Allow all interfaces (wildcard bind)",
                settings.allowAllInterfaces()
        );
        port = spin(settings.port(), 1, 65535, 1);
        maxPage = spin(settings.maxPageSize(), 1, 5000, 50);
        maxMessage = spin(settings.maxMessageMiB(), 1, 100, 1);
        maxPost = spin(settings.maxRequestBodyKiB(), 1, 4096, 64);
        maxScan = spin(settings.maxScanItems(), 1000, 2_000_000, 10_000);
        queryTimeout = spin(settings.queryTimeoutMs(), 250, 120_000, 250);
        workers = spin(settings.workerThreads(), 2, 128, 1);
        concurrentQueries = spin(settings.maxConcurrentQueries(), 1, 64, 1);
        concurrentEvents = spin(settings.maxConcurrentEvents(), 1, 128, 1);
        eventBuffer = spin(settings.eventBufferSize(), 100, 1_000_000, 1000);
        rateLimit = spin(settings.rateLimitPerMinute(), 60, 100_000, 60);
        auditSize = spin(settings.auditMaxMiB(), 1, 1024, 1);
        auditFiles = spin(settings.auditRetainedFiles(), 1, 100, 1);
        rawEnabled = new JCheckBox("Enable exact raw endpoints (still requires history:raw)", settings.rawAccessEnabled());
        auditEnabled = new JCheckBox("Write rotating JSONL access audit log", settings.auditEnabled());
        autoStart = new JCheckBox("Start API when extension loads", settings.autoStart());
        regexEnabled = new JCheckBox("Allow regex search", settings.regexEnabled());
        redactionEnabled = new JCheckBox("Redact structured outputs", settings.redactionEnabled());
        auditPath = new JTextField(settings.auditLogPath());
        redactedHeaders = new JTextField(settings.redactedHeaderNames());
        redactedParameters = new JTextField(settings.redactedParameterNames());
        replacement = new JTextField(settings.redactionReplacement());

        tokenList.setEditable(false);
        tokenList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, tokenList.getFont().getSize()));
        bindingNotice.setFont(bindingNotice.getFont().deriveFont(Font.BOLD));
        bindAddress.setMaximumRowCount(20);
        bindAddress.setToolTipText("Select one IP address assigned to an active local network interface");
        refreshBindingChoices(settings.bindAddress());
        bindAddress.addActionListener(ignored -> updateBindingControls());
        refreshInterfaces.addActionListener(ignored -> refreshBindingChoices(selectedBindAddress()));
        allowAllInterfaces.addActionListener(ignored -> confirmWildcardBinding());
        updateBindingControls();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Runtime", runtimePanel());
        tabs.addTab("Client tokens", tokenPanel());
        tabs.addTab("Security", securityPanel());
        add(tabs, BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);

        timer = new Timer(1000, ignored -> refresh());
        timer.start();
        refresh();
        tokens.bootstrapToken().ifPresent(this::showTokenOnce);
    }

    public void close() {
        timer.stop();
    }

    private JPanel runtimePanel() {
        JPanel panel = form();
        int row = 0;

        JPanel bindSelector = new JPanel(new BorderLayout(6, 0));
        bindSelector.add(bindAddress, BorderLayout.CENTER);
        bindSelector.add(refreshInterfaces, BorderLayout.EAST);
        add(panel, row++, "Bind interface/address", bindSelector);
        add(panel, row++, "Network exposure", allowAllInterfaces);
        add(panel, row++, "Listener port", port);
        add(panel, row++, "HTTP worker threads", workers);
        add(panel, row++, "Concurrent history queries", concurrentQueries);
        add(panel, row++, "Concurrent event streams", concurrentEvents);
        add(panel, row++, "Event ring capacity", eventBuffer);
        add(panel, row++, "Requests/token/minute", rateLimit);
        add(panel, row++, "Maximum page size", maxPage);
        add(panel, row++, "Maximum raw message (MiB)", maxMessage);
        add(panel, row++, "Maximum POST body (KiB)", maxPost);
        add(panel, row++, "Maximum scanned items", maxScan);
        add(panel, row++, "Query timeout (ms)", queryTimeout);
        add(panel, row++, "Startup", autoStart);
        addWide(panel, row, bindingNotice);
        return wrapNorth(panel);
    }

    private JPanel tokenPanel() {
        refreshTokens();
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton read = new JButton("Create read/event token");
        read.addActionListener(e -> createToken(false));
        JButton admin = new JButton("Create 90-day admin/raw token");
        admin.addActionListener(e -> createToken(true));
        JButton revoke = new JButton("Revoke by ID");
        revoke.addActionListener(e -> revokeToken());
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refreshTokens());
        buttons.add(read);
        buttons.add(admin);
        buttons.add(revoke);
        buttons.add(refresh);

        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(new JScrollPane(tokenList), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        panel.setBorder(BorderFactory.createTitledBorder(
                "Project-scoped tokens (secrets are displayed only once)"
        ));
        return panel;
    }

    private JPanel securityPanel() {
        JPanel panel = form();
        int row = 0;
        add(panel, row++, "Raw access", rawEnabled);
        add(panel, row++, "Audit", auditEnabled);
        add(panel, row++, "Audit path", auditPath);
        add(panel, row++, "Rotate at (MiB)", auditSize);
        add(panel, row++, "Retained files", auditFiles);
        add(panel, row++, "Search", regexEnabled);
        add(panel, row++, "Redaction", redactionEnabled);
        add(panel, row++, "Sensitive headers", redactedHeaders);
        add(panel, row++, "Sensitive parameters", redactedParameters);
        add(panel, row++, "Replacement", replacement);
        addWide(panel, row, note(
                "Raw endpoints are disabled by default and require a separate history:raw scope. "
                        + "Audit records metadata only; tokens, query strings and HTTP traffic are never written to the audit log."
        ));
        return wrapNorth(panel);
    }

    private JPanel actions() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton save = new JButton("Save & restart");
        save.addActionListener(e -> apply(true));
        JButton start = new JButton("Start");
        start.addActionListener(e -> apply(false));
        JButton stop = new JButton("Stop");
        stop.addActionListener(e -> {
            controller.stop();
            refresh();
        });
        JButton dashboard = new JButton("Open dashboard");
        dashboard.addActionListener(e -> open("/ui/"));
        panel.add(save);
        panel.add(start);
        panel.add(stop);
        panel.add(Box.createHorizontalStrut(12));
        panel.add(dashboard);
        panel.add(status);
        return panel;
    }

    private void refreshBindingChoices(String preferredAddress) {
        List<NetworkInterfaceCatalog.AddressOption> options = new ArrayList<>(NetworkInterfaceCatalog.addresses());
        NetworkInterfaceCatalog.AddressOption selected = null;
        for (NetworkInterfaceCatalog.AddressOption option : options) {
            if (option.address().equals(preferredAddress)) {
                selected = option;
                break;
            }
        }
        if (selected == null) {
            try {
                selected = NetworkInterfaceCatalog.savedAddress(preferredAddress);
                options.add(0, selected);
            } catch (RuntimeException ignored) {
                selected = options.stream()
                        .filter(option -> option.address().equals(ApiSettings.DEFAULT_BIND_ADDRESS))
                        .findFirst()
                        .orElse(options.getFirst());
            }
        }
        bindAddress.setModel(new DefaultComboBoxModel<>(options.toArray(NetworkInterfaceCatalog.AddressOption[]::new)));
        bindAddress.setSelectedItem(selected);
        updateBindingControls();
    }

    private void confirmWildcardBinding() {
        if (allowAllInterfaces.isSelected()) {
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    "Allowing all interfaces exposes the REST API on every active network adapter.\n\n"
                            + "Use host firewall rules, one scoped token per client, redaction, and TLS or a trusted private network.\n"
                            + "Continue with wildcard binding?",
                    "Enable network-wide API binding?",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (choice != JOptionPane.YES_OPTION) allowAllInterfaces.setSelected(false);
        }
        updateBindingControls();
    }

    private void updateBindingControls() {
        boolean wildcard = allowAllInterfaces.isSelected();
        bindAddress.setEnabled(!wildcard);
        refreshInterfaces.setEnabled(!wildcard);
        NetworkInterfaceCatalog.AddressOption selected = selectedAddressOption();
        if (wildcard) {
            bindingNotice.setText(
                    "WARNING: wildcard mode accepts connections on all active IPv4/IPv6 interfaces. "
                            + "The dashboard remains available locally on 127.0.0.1; remote clients must use a host interface address."
            );
        } else if (selected == null) {
            bindingNotice.setText("Select an active local interface address before starting the API.");
        } else if (!selected.available()) {
            bindingNotice.setText(
                    "WARNING: the saved bind address is not currently detected. The API will not start until that interface/address is available."
            );
        } else if (selected.loopback()) {
            bindingNotice.setText(
                    "Loopback-only binding is recommended. The bounded worker queue, rate limits, scan caps, timeouts and event ring protect Burp from runaway clients."
            );
        } else {
            bindingNotice.setText(
                    "WARNING: this address is reachable through the selected network interface. Restrict inbound access with a host firewall and least-privilege client tokens."
            );
        }
    }

    private NetworkInterfaceCatalog.AddressOption selectedAddressOption() {
        Object selected = bindAddress.getSelectedItem();
        return selected instanceof NetworkInterfaceCatalog.AddressOption option ? option : null;
    }

    private String selectedBindAddress() {
        NetworkInterfaceCatalog.AddressOption selected = selectedAddressOption();
        return selected == null ? ApiSettings.DEFAULT_BIND_ADDRESS : selected.address();
    }

    private void createToken(boolean admin) {
        String label = JOptionPane.showInputDialog(
                this,
                "Client/token label:",
                admin ? "admin-client" : "read-client"
        );
        if (label == null) return;
        AccessTokenStore.GeneratedToken token = admin
                ? tokens.create(label, EnumSet.allOf(AccessScope.class), Instant.now().plus(90, ChronoUnit.DAYS))
                : tokens.create(
                        label,
                        EnumSet.of(AccessScope.HISTORY_READ, AccessScope.HISTORY_EVENTS, AccessScope.METRICS_READ),
                        null
                );
        showTokenOnce(token);
        refreshTokens();
    }

    private void revokeToken() {
        String id = JOptionPane.showInputDialog(this, "Token ID to revoke:");
        if (id == null) return;
        JOptionPane.showMessageDialog(this, tokens.revoke(id.trim()) ? "Token revoked." : "Token ID not found.");
        refreshTokens();
    }

    private void showTokenOnce(AccessTokenStore.GeneratedToken token) {
        JTextArea area = note(
                "TOKEN (shown once):\n" + token.secret() + "\n\nID: " + token.id() + "\nScopes: " + token.scopes()
        );
        area.setRows(7);
        JButton copy = new JButton("Copy token");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(token.secret()),
                null
        ));
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        panel.add(copy, BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(this, panel, "New API token", JOptionPane.WARNING_MESSAGE);
    }

    private void refreshTokens() {
        StringBuilder text = new StringBuilder();
        for (AccessTokenStore.TokenInfo token : tokens.list()) {
            text.append(token.id()).append("  ")
                    .append(token.label()).append("  ")
                    .append(token.scopes()).append("  expires=")
                    .append(token.expiresAt() == null ? "never" : token.expiresAt())
                    .append(token.expired() ? " [EXPIRED]" : "")
                    .append('\n');
        }
        tokenList.setText(text.isEmpty() ? "No tokens" : text.toString());
    }

    private void apply(boolean restart) {
        try {
            ApiSettings settings = read();
            if (settings.networkExposed()) {
                String exposure = settings.allowAllInterfaces()
                        ? "all active network interfaces"
                        : "the selected non-loopback interface at " + settings.bindAddress();
                int choice = JOptionPane.showConfirmDialog(
                        this,
                        "The REST API will be reachable through " + exposure + ".\n\n"
                                + "Confirm that firewall policy, transport security, token scopes, and audit retention are configured.",
                        "Confirm network exposure",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE
                );
                if (choice != JOptionPane.YES_OPTION) return;
            }
            store.save(settings);
            if (restart || controller.status().running()) controller.restart(settings);
            else controller.start(settings);
            refresh();
        } catch (RuntimeException e) {
            api.logging().logToError("Unable to apply REST API settings", e);
            JOptionPane.showMessageDialog(
                    this,
                    e.getMessage(),
                    "Burp History REST API",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private ApiSettings read() {
        return new ApiSettings(
                selectedBindAddress(),
                allowAllInterfaces.isSelected(),
                value(port),
                value(maxPage),
                value(maxMessage),
                value(maxPost),
                value(maxScan),
                value(queryTimeout),
                value(workers),
                value(concurrentQueries),
                value(concurrentEvents),
                value(eventBuffer),
                value(rateLimit),
                rawEnabled.isSelected(),
                auditEnabled.isSelected(),
                auditPath.getText(),
                value(auditSize),
                value(auditFiles),
                autoStart.isSelected(),
                regexEnabled.isSelected(),
                redactionEnabled.isSelected(),
                redactedHeaders.getText(),
                redactedParameters.getText(),
                replacement.getText()
        );
    }

    private void refresh() {
        ApiController.Status current = controller.status();
        if (current.running()) {
            status.setText(
                    "Running: " + current.listenerDescription()
                            + (current.networkExposed() ? " [NETWORK-EXPOSED]" : "")
                            + " | tokens=" + current.tokenCount()
            );
        } else {
            status.setText(
                    current.lastError() == null
                            ? "Stopped | tokens=" + current.tokenCount()
                            : "Stopped: " + current.lastError()
            );
        }
    }

    private void open(String path) {
        ApiController.Status current = controller.status();
        if (!current.running()) {
            JOptionPane.showMessageDialog(this, "Start the API first.");
            return;
        }
        String url = current.baseUrl() + path;
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
        }
    }

    private static int value(JSpinner spinner) {
        return (Integer) spinner.getValue();
    }

    private static JSpinner spin(int value, int min, int max, int step) {
        return new JSpinner(new SpinnerNumberModel(value, min, max, step));
    }

    private static JPanel form() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return panel;
    }

    private static void add(JPanel panel, int row, String label, Component component) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(3, 3, 3, 3);
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel(label + ":"), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(component, constraints);
    }

    private static void addWide(JPanel panel, int row, Component component) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(8, 3, 3, 3);
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(component, constraints);
    }

    private static JPanel wrapNorth(Component component) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(component, BorderLayout.NORTH);
        return panel;
    }

    private static JTextArea note(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        return area;
    }
}
