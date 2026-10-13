package com.cameomcp.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Properties;

/**
 * Plugin settings, stored in {@code ~/.cameo-mcp/settings.properties}.
 * <p>
 * Precedence for the port: JVM system property {@code cameo.mcp.port} (set in Cameo's
 * .vmoptions), then environment variable {@code CAMEO_MCP_PORT}, then the settings file,
 * then 18740. The same pattern applies to host and token.
 */
public final class Settings {

    public static final int DEFAULT_PORT = 18740;
    public static final String DEFAULT_HOST = "127.0.0.1";

    // Public fields because the status window edits them directly before save().
    public int port = DEFAULT_PORT;
    public String host = DEFAULT_HOST;
    /** Bearer token clients must send; empty means no authentication (loopback only). */
    public String token = "";
    // Start the server when Cameo starts; off means the user starts it from the MCP menu.
    public boolean autoStart = true;
    // When false, cameo_execute_macro and cameo_execute_probe are refused (see Catalog.isScriptTool).
    public boolean allowMacros = true;
    /** off | errors | all : which tool calls are echoed to the Notification window. */
    public String guiLogLevel = "errors";

    /** Where the value of the port came from, shown in the status window. */
    public String portSource = "default";

    /** The settings folder: {@code -Dcameo.mcp.home} if set, else {@code ~/.cameo-mcp}. */
    public static File directory() {
        String override = System.getProperty("cameo.mcp.home");
        return override != null ? new File(override) : new File(System.getProperty("user.home"), ".cameo-mcp");
    }

    /** The settings file inside {@link #directory()}. */
    public static File file() {
        return new File(directory(), "settings.properties");
    }

    /**
     * Reads the settings file, then applies overrides: environment variables, then JVM options,
     * which win. Never throws; an unreadable file is logged and defaults are used.
     */
    public static Settings load() {
        Settings s = new Settings();
        Properties p = new Properties();
        File f = file();
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException e) {
                CameoLog.warn("Could not read " + f + ": " + e.getMessage());
            }
        }
        s.port = parsePort(p.getProperty("port"), DEFAULT_PORT);
        if (p.getProperty("port") != null) {
            s.portSource = "settings file";
        }
        s.host = p.getProperty("host", DEFAULT_HOST).trim();
        s.token = p.getProperty("token", "").trim();
        s.autoStart = Boolean.parseBoolean(p.getProperty("autoStart", "true"));
        s.allowMacros = Boolean.parseBoolean(p.getProperty("allowMacros", "true"));
        s.guiLogLevel = p.getProperty("guiLogLevel", "errors").trim();

        String env = System.getenv("CAMEO_MCP_PORT");
        if (env != null && !env.trim().isEmpty()) {
            s.port = parsePort(env, s.port);
            s.portSource = "CAMEO_MCP_PORT environment variable";
        }
        String sys = System.getProperty("cameo.mcp.port");
        if (sys != null && !sys.trim().isEmpty()) {
            s.port = parsePort(sys, s.port);
            s.portSource = "-Dcameo.mcp.port JVM option";
        }
        String sysHost = System.getProperty("cameo.mcp.host");
        if (sysHost != null && !sysHost.trim().isEmpty()) {
            s.host = sysHost.trim();
        }
        String sysToken = System.getProperty("cameo.mcp.token", System.getenv("CAMEO_MCP_TOKEN"));
        if (sysToken != null && !sysToken.trim().isEmpty()) {
            s.token = sysToken.trim();
        }
        return s;
    }

    /** Writes the current values to the settings file, creating the folder if needed. */
    public void save() throws IOException {
        File dir = directory();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        Properties p = new Properties();
        p.setProperty("port", String.valueOf(port));
        p.setProperty("host", host);
        p.setProperty("token", token == null ? "" : token);
        p.setProperty("autoStart", String.valueOf(autoStart));
        p.setProperty("allowMacros", String.valueOf(allowMacros));
        p.setProperty("guiLogLevel", guiLogLevel);
        try (OutputStream out = new FileOutputStream(file())) {
            p.store(out, "Cameo MCP settings. Restart the MCP server (MCP menu) after editing by hand.");
        }
    }

    /** Parses a port number; out-of-range or non-numeric text gives {@code dflt}. Port 0 means "any free port". */
    public static int parsePort(String text, int dflt) {
        if (text == null) {
            return dflt;
        }
        try {
            int v = Integer.parseInt(text.trim());
            return v >= 0 && v <= 65535 ? v : dflt;
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** A new random 48-character hex token from a secure random source. */
    public static String newToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    /** True when the server only listens on this machine. */
    public boolean isLoopback() {
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1");
    }
}
