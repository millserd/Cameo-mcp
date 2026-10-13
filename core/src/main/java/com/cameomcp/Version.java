package com.cameomcp;

import java.io.InputStream;
import java.util.Properties;

/** Build version of Cameo MCP, read from the packaged version.properties. */
public final class Version {

    private static final String VALUE = load();

    private Version() {
    }

    public static String get() {
        return VALUE;
    }

    private static String load() {
        try (InputStream in = Version.class.getResourceAsStream("/com/cameomcp/version.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version");
                if (v != null && !v.contains("$")) {
                    return v;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "dev";
    }
}
