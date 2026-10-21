package com.cameomcp;

import java.io.InputStream;
import java.util.Properties;

/** Build version of Cameo MCP, read from the packaged version.properties. */
public final class Version {

    /** The version string, read once when the class loads. */
    private static final String VALUE = load();

    /** Static methods only. */
    private Version() {
    }

    /**
     * The packaged version such as {@code 1.0.0}, or {@code dev} when running from unbuilt sources.
     *
     * @return the version string, never null
     */
    public static String get() {
        return VALUE;
    }

    /**
     * Reads the version from the packaged properties file. Gradle expands ${version} into
     * version.properties at build time; an unexpanded "$" means the classes came from an IDE or
     * devtools compile, so "dev" is reported instead. Read errors also give "dev".
     *
     * @return the version, or "dev"
     */
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
