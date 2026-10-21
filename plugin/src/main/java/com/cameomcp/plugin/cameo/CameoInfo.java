package com.cameomcp.plugin.cameo;

import com.cameomcp.json.Json;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;

import java.util.Map;

/**
 * Facts about the running Cameo: product, version, install folder, open project.
 * Only APIs present in both 2024x and 2026x are used; the rest is best effort.
 */
public final class CameoInfo {

    /** Static methods only. */
    private CameoInfo() {
    }

    /**
     * True when a project is open. Never throws, so status checks work even outside Cameo.
     *
     * @return whether Cameo has an active project
     */
    public static boolean hasProject() {
        try {
            return Application.getInstance().getProject() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Product name, version, install folder and open project (name, file, dirty, remote).
     * Keys whose value is unknown are left out rather than set to null, except {@code project}.
     *
     * @return a new map with installRoot, product, version (when known) and project (null when none is open)
     */
    public static Map<String, Object> describe() {
        Map<String, Object> m = Json.obj();
        // There is no public version API in 2024x/2026x; read the product info the launcher
        // records in system properties, then fall back to the install folder name.
        String installRoot = null;
        try {
            installRoot = Application.environment().getInstallRoot();
        } catch (Throwable ignored) {
            // not inside Cameo
        }
        put(m, "installRoot", installRoot);
        put(m, "product", firstProperty("md.product.name", "product.name", "APP_NAME"));
        String version = firstProperty("md.version", "product.version", "md.build.version", "magicdraw.version");
        if (version == null && installRoot != null) {
            // Install folders are usually named like "Cameo Systems Modeler 2026x Refresh1".
            java.util.regex.Matcher x = java.util.regex.Pattern.compile("(20\\d\\dx(?:[ _]?Refresh\\d+)?)",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(installRoot);
            if (x.find()) {
                version = x.group(1);
            }
        }
        put(m, "version", version);
        try {
            Project p = Application.getInstance().getProject();
            if (p != null) {
                m.put("project", Json.obj("name", p.getName(), "file", p.getFileName(), "dirty", p.isDirty(),
                        "remote", p.isRemote()));
            } else {
                m.put("project", null);
            }
        } catch (Throwable t) {
            m.put("project", null);
        }
        return m;
    }

    /**
     * Adds the entry only when there is a value.
     *
     * @param m the map to fill
     * @param key entry name
     * @param value the value; null or empty values are skipped
     */
    private static void put(Map<String, Object> m, String key, String value) {
        if (value != null && !value.isEmpty()) {
            m.put(key, value);
        }
    }

    /**
     * Finds the first non-blank system property among the names.
     *
     * @param names property names, in order of preference
     * @return the trimmed value, or null
     */
    private static String firstProperty(String... names) {
        for (String n : names) {
            String v = System.getProperty(n);
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return null;
    }
}
