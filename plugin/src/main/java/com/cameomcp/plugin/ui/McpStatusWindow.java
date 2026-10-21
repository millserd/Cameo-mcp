package com.cameomcp.plugin.ui;

import com.cameomcp.json.Json;
import com.cameomcp.plugin.CameoLog;
import com.cameomcp.plugin.McpRuntime;
import com.cameomcp.plugin.Settings;
import com.cameomcp.plugin.cameo.CameoInfo;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Map;

/**
 * The "MCP Server Status" window: shows whether the server is up, where clients connect,
 * who is connected and what they are doing; lets the user start/stop/restart, change the
 * port and other settings, run a connection self-test and copy client configurations.
 */
public final class McpStatusWindow extends JDialog {

    /**
     * One window per Cameo session; reopening the menu item brings the same window to the front. EDT only.
     */
    private static McpStatusWindow instance;

    /** The runtime this window shows and controls. */
    private final McpRuntime rt;
    // Status tab labels, refreshed every second by refresh().
    /** Running / Stopped / Error, in bold and colored. */
    private final JLabel stateLabel = new JLabel();
    /** The MCP endpoint URL, selectable for copying. */
    private final JTextField endpointField = readOnlyField();
    /** Port and where its value came from. */
    private final JLabel portLabel = new JLabel();
    /** Bind address and whether a token is required. */
    private final JLabel hostLabel = new JLabel();
    /** Time since the server started. */
    private final JLabel uptimeLabel = new JLabel();
    /** Tool call and error counters. */
    private final JLabel callsLabel = new JLabel();
    /** Last tool called and when. */
    private final JLabel lastCallLabel = new JLabel();
    /** Open project name and dirty flag. */
    private final JLabel projectLabel = new JLabel();
    /** Cameo product, version and Java runtime. */
    private final JLabel cameoLabel = new JLabel();
    /** Plugin version and macro switch state. */
    private final JLabel versionLabel = new JLabel();
    /** Last start failure, in red. */
    private final JLabel errorLabel = new JLabel();
    /** Starts the server; enabled while stopped. */
    private final JButton startButton = new JButton("Start");
    /** Stops the server; enabled while running. */
    private final JButton stopButton = new JButton("Stop");
    /** Restarts the server; enabled while running. */
    private final JButton restartButton = new JButton("Restart");
    /** Connected MCP sessions, one row each; read-only. */
    private final DefaultTableModel clientsModel = new DefaultTableModel(
            new Object[]{"Client", "Version", "MCP protocol", "Requests", "Tool calls", "Errors", "Last seen"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    /** Activity history text (Activity tab), monospaced. */
    private final JTextArea activity = new JTextArea();
    // Settings tab controls; loaded from Settings when the window opens, saved by applySettings().
    /** Port setting (1 to 65535). */
    private final JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(Settings.DEFAULT_PORT, 1, 65535, 1));
    /** Bind address setting; editable so any address can be typed. */
    private final JComboBox<String> hostBox = new JComboBox<>(new String[]{"127.0.0.1", "0.0.0.0"});
    /** Whether a bearer token is required. */
    private final JCheckBox tokenBox = new JCheckBox("Require bearer token");
    /** The bearer token text. */
    private final JTextField tokenField = new JTextField(32);
    /** Auto-start setting. */
    private final JCheckBox autoStartBox = new JCheckBox("Start the MCP server when Cameo starts");
    /** Macro switch setting. */
    private final JCheckBox macrosBox = new JCheckBox("Allow macro scripts (cameo_execute_macro, cameo_execute_probe)");
    /** Notification window log level: off, errors or all. */
    private final JComboBox<String> logLevelBox = new JComboBox<>(new String[]{"off", "errors", "all"});
    /** One-second refresh timer, running while the window is open. */
    private final Timer timer;
    /**
     * Runtime change listener. Runtime changes can arrive on any thread; it hops to the EDT before touching
     * Swing.
     */
    private final McpRuntime.ChangeListener listener = () -> SwingUtilities.invokeLater(this::refresh);
    /** History size already rendered, so the activity text is only rebuilt when something changed. */
    private int shownHistory = -1;

    /** Opens (or brings to front) the status window. Safe to call from any menu action on the EDT. */
    public static void showWindow() {
        McpRuntime rt = McpRuntime.get();
        if (rt == null) {
            JOptionPane.showMessageDialog(null, "Cameo MCP did not initialize. See the Cameo log for details.",
                    "Cameo MCP", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (instance == null || !instance.isDisplayable()) {
            instance = new McpStatusWindow(mainFrame(), rt);
        }
        instance.loadSettings();
        instance.refresh();
        instance.setVisible(true);
        instance.toFront();
    }

    /**
     * Cameo's main window, used as the dialog owner so the window stays on top of Cameo.
     *
     * @return the main frame, or null when there is none (tests)
     */
    static Frame mainFrame() {
        try {
            Object f = com.nomagic.magicdraw.core.Application.getInstance().getMainFrame();
            if (f instanceof Frame) {
                return (Frame) f;
            }
        } catch (Throwable ignored) {
            // no main frame (tests)
        }
        return null;
    }

    /**
     * Builds the window. Non-modal so the user can keep working in Cameo while watching activity.
     *
     * @param owner the dialog owner, may be null
     * @param rt the runtime to show
     */
    private McpStatusWindow(Frame owner, McpRuntime rt) {
        super(owner, "Cameo MCP Server", false);
        this.rt = rt;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Status", statusTab());
        tabs.addTab("Activity", activityTab());
        tabs.addTab("Settings", settingsTab());
        tabs.addTab("Connect a client", connectTab());
        getContentPane().add(tabs, BorderLayout.CENTER);
        setMinimumSize(new Dimension(640, 480));
        setSize(780, 560);
        setLocationRelativeTo(owner);

        // Poll once a second while open for uptime and counters, which change without events.
        timer = new Timer(1000, e -> refresh());
        rt.addListener(listener);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                timer.start();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                timer.stop();
                rt.removeListener(listener);
            }
        });
    }

    // ------------------------------------------------------------------ tabs

    /**
     * Server state, endpoint, counters, project, connected clients, and start/stop/test buttons.
     *
     * @return the Status tab
     */
    private JComponent statusTab() {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        stateLabel.setFont(stateLabel.getFont().deriveFont(Font.BOLD, stateLabel.getFont().getSize() + 4f));
        int row = 0;
        addRow(grid, row++, "Server", stateLabel);
        JPanel endpointRow = new JPanel(new BorderLayout(6, 0));
        endpointRow.add(endpointField, BorderLayout.CENTER);
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> copy(rt.endpointUrl(), "Endpoint URL"));
        endpointRow.add(copy, BorderLayout.EAST);
        addRow(grid, row++, "MCP endpoint", endpointRow);
        addRow(grid, row++, "Port", portLabel);
        addRow(grid, row++, "Bound to", hostLabel);
        addRow(grid, row++, "Uptime", uptimeLabel);
        addRow(grid, row++, "Tool calls", callsLabel);
        addRow(grid, row++, "Last tool call", lastCallLabel);
        addRow(grid, row++, "Project", projectLabel);
        addRow(grid, row++, "Cameo", cameoLabel);
        addRow(grid, row++, "Plugin", versionLabel);
        errorLabel.setForeground(new Color(0xB00020));
        addRow(grid, row++, "Last error", errorLabel);

        JTable clients = new JTable(clientsModel);
        clients.setFillsViewportHeight(true);
        JScrollPane clientScroll = new JScrollPane(clients);
        clientScroll.setPreferredSize(new Dimension(600, 110));
        clientScroll.setBorder(BorderFactory.createTitledBorder("Connected clients"));

        startButton.addActionListener(e -> background("Starting", rt::start));
        stopButton.addActionListener(e -> background("Stopping", () -> {
            rt.stop();
            return true;
        }));
        restartButton.addActionListener(e -> background("Restarting", rt::restart));
        JButton test = new JButton("Test connection");
        test.addActionListener(e -> runSelfTest());
        JButton log = new JButton("Write status to Cameo log");
        log.addActionListener(e -> {
            CameoLog.info("Status: " + Json.write(rt.status()));
            rt.event("info", "Status written to the Cameo log");
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(startButton);
        buttons.add(stopButton);
        buttons.add(restartButton);
        buttons.add(Box.createHorizontalStrut(12));
        buttons.add(test);
        buttons.add(log);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(grid, BorderLayout.NORTH);
        panel.add(clientScroll, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * Scrolling list of recent events (connections, tool calls, errors).
     *
     * @return the Activity tab
     */
    private JComponent activityTab() {
        activity.setEditable(false);
        activity.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JScrollPane(activity), BorderLayout.CENTER);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton copy = new JButton("Copy all");
        copy.addActionListener(e -> copy(activity.getText(), "Activity"));
        south.add(copy);
        south.add(new JLabel("Every tool call is also written to Cameo's log file (md.log), prefixed [Cameo MCP]."));
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * Port, bind address, token, auto-start, macro switch and Notification-window log level.
     *
     * @return the Settings tab
     */
    private JComponent settingsTab() {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        int row = 0;
        addRow(grid, row++, "Port", portSpinner);
        hostBox.setEditable(true);
        addRow(grid, row++, "Bind address", hostBox);
        addRow(grid, row++, "", note("127.0.0.1 accepts local clients only (recommended). 0.0.0.0 exposes Cameo to "
                + "your network; use it only with a token."));
        JPanel tokenRow = new JPanel(new BorderLayout(6, 0));
        tokenRow.add(tokenBox, BorderLayout.WEST);
        tokenRow.add(tokenField, BorderLayout.CENTER);
        JButton gen = new JButton("Generate");
        gen.addActionListener(e -> {
            tokenField.setText(Settings.newToken());
            tokenBox.setSelected(true);
        });
        tokenRow.add(gen, BorderLayout.EAST);
        addRow(grid, row++, "Authentication", tokenRow);
        addRow(grid, row++, "", autoStartBox);
        addRow(grid, row++, "", macrosBox);
        addRow(grid, row++, "Notification window", logLevelBox);
        addRow(grid, row++, "", note("Which tool calls are echoed to Cameo's Notification window. "
                + "Server start/stop and client connections are always shown."));
        addRow(grid, row++, "", note("Saved to " + Settings.file() + ". A -Dcameo.mcp.port JVM option or the "
                + "CAMEO_MCP_PORT environment variable overrides the port."));
        JButton apply = new JButton("Save and restart server");
        apply.addActionListener(e -> applySettings());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(apply);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(grid, BorderLayout.NORTH);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * Ready-to-copy client configurations with the live port, token and paths filled in.
     *
     * @return the Connect a client tab
     */
    private JComponent connectTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(snippet("Any MCP client with HTTP support (mcpServers JSON)", () -> ClientConfigs.httpJson(rt)));
        panel.add(snippet("stdio-only clients (uses Cameo's Java to run the launcher)", () -> ClientConfigs.stdioJson(rt)));
        return new JScrollPane(panel);
    }

    /**
     * A titled box holding one configuration and a Copy button; the text is regenerated on copy.
     *
     * @param title box title, also used in the "copied" activity line
     * @param text produces the configuration text
     * @return the box
     */
    private JComponent snippet(String title, java.util.function.Supplier<String> text) {
        JTextArea area = new JTextArea(text.get());
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setLineWrap(true);
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> {
            area.setText(text.get());
            copy(area.getText(), title);
        });
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(new JScrollPane(area), BorderLayout.CENTER);
        p.add(copy, BorderLayout.EAST);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    // ------------------------------------------------------------------ behavior

    /** Copies the runtime's settings into the form. */
    private void loadSettings() {
        Settings s = rt.settings();
        portSpinner.setValue(s.port);
        hostBox.setSelectedItem(s.host);
        tokenBox.setSelected(s.token != null && !s.token.isEmpty());
        tokenField.setText(s.token == null ? "" : s.token);
        autoStartBox.setSelected(s.autoStart);
        macrosBox.setSelected(s.allowMacros);
        logLevelBox.setSelectedItem(s.guiLogLevel);
    }

    /**
     * Validates the form, saves it to the settings file and applies it (restarting the server if
     * it was running). Warns before exposing the server on the network without a token.
     */
    private void applySettings() {
        Settings s = new Settings();
        s.port = (Integer) portSpinner.getValue();
        s.host = String.valueOf(hostBox.getSelectedItem()).trim();
        s.token = tokenBox.isSelected() ? tokenField.getText().trim() : "";
        s.autoStart = autoStartBox.isSelected();
        s.allowMacros = macrosBox.isSelected();
        s.guiLogLevel = String.valueOf(logLevelBox.getSelectedItem());
        s.portSource = "settings file";
        if (tokenBox.isSelected() && s.token.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Enter or generate a token, or untick 'Require bearer token'.",
                    "Cameo MCP", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!s.isLoopback() && s.token.isEmpty()) {
            int answer = JOptionPane.showConfirmDialog(this, "Binding to " + s.host + " without a token lets anyone on "
                    + "your network control Cameo, including running scripts. Continue?", "Cameo MCP",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
        }
        try {
            s.save();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save settings: " + ex.getMessage(), "Cameo MCP",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        // A JVM option overrides the saved port at the next Cameo start, so tell the user now.
        String sysPort = System.getProperty("cameo.mcp.port");
        if (sysPort != null && !sysPort.trim().isEmpty()) {
            rt.event("warn", "-Dcameo.mcp.port=" + sysPort + " is set in Cameo's JVM options and wins over this setting "
                    + "after a Cameo restart.");
        }
        background("Applying settings", () -> rt.apply(s));
        CameoLog.info("Settings saved: port " + s.port + ", bind " + s.host + ", token " + (s.token.isEmpty() ? "off" : "on")
                + ", macros " + (s.allowMacros ? "allowed" : "blocked") + ".");
    }

    /** Same self-test as the MCP menu item, reported in a dialog and the activity list. */
    private void runSelfTest() {
        rt.event("info", "Connection self-test started");
        new SwingWorker<List<ConnectionTest.Step>, Void>() {
            @Override
            protected List<ConnectionTest.Step> doInBackground() {
                return ConnectionTest.run(rt);
            }

            @Override
            protected void done() {
                try {
                    List<ConnectionTest.Step> steps = get();
                    StringBuilder sb = new StringBuilder();
                    boolean ok = true;
                    for (ConnectionTest.Step s : steps) {
                        sb.append(s).append('\n');
                        ok &= s.ok;
                    }
                    String summary = ok ? "Connection test passed: MCP clients can connect at " + rt.endpointUrl()
                            : "Connection test FAILED";
                    CameoLog.info(summary + "\n" + sb);
                    rt.event(ok ? "info" : "error", summary);
                    JOptionPane.showMessageDialog(McpStatusWindow.this, summary + "\n\n" + sb, "Cameo MCP self-test",
                            ok ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
                } catch (Exception e) {
                    rt.event("error", "Self-test crashed: " + e);
                }
            }
        }.execute();
    }

    /** A start/stop/restart step returning false on failure. */
    private interface Action {
        /**
         * Performs the step off the EDT.
         *
         * @return true on success
         * @throws Exception on unexpected failure (shown in a dialog)
         */
        boolean run() throws Exception;
    }

    /**
     * Runs a server action off the EDT with the buttons disabled, then reports any failure.
     *
     * @param label action name for the error dialog
     * @param action the step
     */
    private void background(String label, Action action) {
        startButton.setEnabled(false);
        stopButton.setEnabled(false);
        restartButton.setEnabled(false);
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                return action.run();
            }

            @Override
            protected void done() {
                try {
                    if (!get()) {
                        JOptionPane.showMessageDialog(McpStatusWindow.this, label + " failed: " + rt.lastError(),
                                "Cameo MCP", JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(McpStatusWindow.this, label + " failed: " + e.getMessage(),
                            "Cameo MCP", JOptionPane.ERROR_MESSAGE);
                }
                refresh();
            }
        }.execute();
    }

    /** Rebuilds every label from rt.status(); runs on the EDT. */
    @SuppressWarnings("unchecked")
    private void refresh() {
        Map<String, Object> s = rt.status();
        boolean running = Boolean.TRUE.equals(s.get("running"));
        String state = String.valueOf(s.get("state"));
        stateLabel.setText(running ? "● Running" : "error".equals(state) ? "● Error" : "● Stopped");
        stateLabel.setForeground(running ? new Color(0x1B7F3A) : "error".equals(state) ? new Color(0xB00020) : Color.GRAY);
        endpointField.setText(String.valueOf(s.get("endpoint")));
        portLabel.setText(s.get("port") + "   (from " + s.get("portSource") + ")");
        hostLabel.setText(s.get("host") + (Boolean.TRUE.equals(s.get("authRequired")) ? "   (bearer token required)" : "   (no token)"));
        Object up = s.get("uptimeSeconds");
        uptimeLabel.setText(up == null ? "-" : formatDuration(((Number) up).longValue()));
        callsLabel.setText(s.get("toolCalls") + " calls, " + s.get("toolErrors") + " errors, " + s.get("toolCount")
                + " tools available");
        Object last = s.get("lastToolCall");
        lastCallLabel.setText(last instanceof Map ? ((Map<String, Object>) last).get("tool") + " at "
                + ((Map<String, Object>) last).get("at") : "-");
        Map<String, Object> cameo = (Map<String, Object>) s.get("cameo");
        Object project = cameo == null ? null : cameo.get("project");
        projectLabel.setText(project instanceof Map ? ((Map<String, Object>) project).get("name")
                + (Boolean.TRUE.equals(((Map<String, Object>) project).get("dirty")) ? " (unsaved changes)" : "")
                : CameoInfo.hasProject() ? "open" : "No project open - model tools will report NO_PROJECT");
        cameoLabel.setText(cameo == null ? "-" : (cameo.get("product") == null ? "" : cameo.get("product") + " ")
                + (cameo.get("version") == null ? "" : cameo.get("version")) + "   Java " + s.get("java"));
        versionLabel.setText("Cameo MCP " + s.get("version") + (Boolean.TRUE.equals(s.get("macrosAllowed"))
                ? "   macros allowed" : "   macros blocked"));
        errorLabel.setText(s.get("lastError") == null ? "none" : String.valueOf(s.get("lastError")));
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        restartButton.setEnabled(running);

        clientsModel.setRowCount(0);
        for (Object o : (List<Object>) s.get("sessions")) {
            Map<String, Object> m = (Map<String, Object>) o;
            clientsModel.addRow(new Object[]{m.get("client"), m.get("clientVersion"), m.get("protocolVersion"),
                    m.get("requests"), m.get("toolCalls"), m.get("errors"), m.get("lastSeen")});
        }

        List<McpRuntime.Event> history = rt.history();
        // Rebuild when the count changed, or when it stayed at the cap but the newest line is new.
        if (history.size() != shownHistory || (!history.isEmpty() && shownHistory == history.size()
                && !activity.getText().endsWith(history.get(history.size() - 1) + "\n"))) {
            StringBuilder sb = new StringBuilder();
            for (McpRuntime.Event e : history) {
                sb.append(e).append('\n');
            }
            activity.setText(sb.toString());
            activity.setCaretPosition(activity.getDocument().getLength());
            shownHistory = history.size();
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Formats a duration as "1h 05m", "3m 07s" or "12s".
     *
     * @param seconds the duration
     * @return the text
     */
    static String formatDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0 ? String.format("%dh %02dm", h, m) : m > 0 ? String.format("%dm %02ds", m, s) : s + "s";
    }

    /**
     * Copies text to the system clipboard and notes it in the activity list.
     *
     * @param text the text to copy
     * @param what what was copied, for the activity line
     */
    private void copy(String text, String what) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        rt.event("info", what + " copied to the clipboard");
    }

    /**
     * A text field the user can select and copy from but not edit.
     *
     * @return the field
     */
    private static JTextField readOnlyField() {
        JTextField f = new JTextField();
        f.setEditable(false);
        return f;
    }

    /**
     * Grey wrapped help text under a setting.
     *
     * @param text the text (HTML is not escaped)
     * @return the label
     */
    private static JLabel note(String text) {
        JLabel l = new JLabel("<html><body style='width:420px'>" + text + "</body></html>");
        l.setForeground(Color.GRAY);
        return l;
    }

    /**
     * Adds a "label: component" row to a two-column GridBag form.
     *
     * @param grid the form panel
     * @param row the row index
     * @param label the left-column text
     * @param value the right-column component
     */
    private static void addRow(JPanel grid, int row, String label, JComponent value) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(3, 0, 3, 12);
        grid.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(3, 0, 3, 0);
        grid.add(value, c);
    }
}
