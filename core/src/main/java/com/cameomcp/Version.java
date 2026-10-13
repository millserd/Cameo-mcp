package com.cameomcp;

import java.io.InputStream;
import java.util.Properties;

/** Build version of Cameo MCP, read from the packaged version.properties. */
public final class Version {

    private static final String VALUE = load();

    private Version() {
    }

    /** The packaged version such as {@code 1.0.0}, or {@code dev} when running from unbuilt sources. */
    public static String get() {
        return VALUE;
    }

    // Gradle expands ${version} into version.properties at build time; an unexpanded "$"
    // means the classes came from an IDE or devtools compile, so report "dev" instead.
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
