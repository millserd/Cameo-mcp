package com.cameomcp.plugin.ui;

import com.cameomcp.plugin.CameoLog;
import com.cameomcp.plugin.McpRuntime;
import com.nomagic.actions.ActionsCategory;
import com.nomagic.actions.ActionsManager;
import com.nomagic.actions.NMAction;
import com.nomagic.actions.AMConfigurator;
import com.nomagic.magicdraw.actions.MDAction;
import com.nomagic.magicdraw.actions.MDActionsCategory;

import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.util.List;

/**
 * Adds the "MCP" menu to Cameo's main menu bar:
 * status window, start/stop/restart, connection test and copy-client-config shortcuts.
 */
public final class McpMenuConfigurator implements AMConfigurator {

    static final String CATEGORY_ID = "CAMEO_MCP_MENU";

    @Override
    public void configure(ActionsManager manager) {
        NMAction existing = manager.getActionFor(CATEGORY_ID);
        ActionsCategory category;
        if (existing instanceof ActionsCategory) {
            category = (ActionsCategory) existing;
        } else {
            category = new MDActionsCategory(CATEGORY_ID, "MCP");
            category.setNested(true);
            manager.addCategory(category);
        }
        category.addAction(new Item("CAMEO_MCP_STATUS", "MCP Server Status...", McpStatusWindow::showWindow, s -> true));
        category.addAction(new Item("CAMEO_MCP_START", "Start Server", () -> run("Start", McpRuntime::start),
                rt -> !rt.isRunning()));
        category.addAction(new Item("CAMEO_MCP_STOP", "Stop Server", () -> run("Stop", rt -> {
            rt.stop();
            return true;
        }), McpRuntime::isRunning));
        category.addAction(new Item("CAMEO_MCP_RESTART", "Restart Server", () -> run("Restart", McpRuntime::restart),
                McpRuntime::isRunning));
        category.addAction(new Item("CAMEO_MCP_TEST", "Test Connection", McpMenuConfigurator::test, rt -> true));
        category.addAction(new Item("CAMEO_MCP_COPY", "Copy Claude Code Command", () -> {
            McpRuntime rt = McpRuntime.get();
            String cmd = ClientConfigs.claudeCode(rt);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(cmd), null);
            CameoLog.info("Copied to clipboard: " + cmd.replaceAll("Bearer \\S+\"", "Bearer ***\""));
        }, rt -> true));
    }

    @Override
    public int getPriority() {
        return AMConfigurator.MEDIUM_PRIORITY;
    }

    private interface RuntimeAction {
        boolean run(McpRuntime rt) throws Exception;
    }

    private static void run(String label, RuntimeAction action) {
        McpRuntime rt = McpRuntime.get();
        if (rt == null) {
            return;
        }
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                return action.run(rt);
            }

            @Override
            protected void done() {
                try {
                    if (!get()) {
                        JOptionPane.showMessageDialog(McpStatusWindow.mainFrame(), label + " failed: " + rt.lastError(),
                                "Cameo MCP", JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception e) {
                    CameoLog.error(label + " failed", e);
                }
            }
        }.execute();
    }

    private static void test() {
        McpRuntime rt = McpRuntime.get();
        if (rt == null) {
            return;
        }
        new SwingWorker<List<ConnectionTest.Step>, Void>() {
            @Override
            protected List<ConnectionTest.Step> doInBackground() {
                return ConnectionTest.run(rt);
            }

            @Override
            protected void done() {
                try {
                    StringBuilder sb = new StringBuilder();
                    boolean ok = true;
                    for (ConnectionTest.Step s : get()) {
                        sb.append(s).append('\n');
                        ok &= s.ok;
                    }
                    String summary = ok ? "MCP connection OK at " + rt.endpointUrl() : "MCP connection test FAILED";
                    CameoLog.info(summary + "\n" + sb);
                    rt.event(ok ? "info" : "error", summary);
                    JOptionPane.showMessageDialog(McpStatusWindow.mainFrame(), summary + "\n\n" + sb,
                            "Cameo MCP", ok ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
                } catch (Exception e) {
                    CameoLog.error("Connection test failed", e);
                }
            }
        }.execute();
    }

    /** A menu item whose enabled state follows the server state. */
    private static final class Item extends MDAction {
        private final Runnable onClick;
        private final java.util.function.Predicate<McpRuntime> enabled;

        Item(String id, String name, Runnable onClick, java.util.function.Predicate<McpRuntime> enabled) {
            super(id, name, null, null);
            this.onClick = onClick;
            this.enabled = enabled;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            try {
                onClick.run();
            } catch (Throwable t) {
                CameoLog.error("MCP menu action failed", t);
            }
        }

        @Override
        public void updateState() {
            McpRuntime rt = McpRuntime.get();
            setEnabled(rt != null && enabled.test(rt));
        }
    }
}
