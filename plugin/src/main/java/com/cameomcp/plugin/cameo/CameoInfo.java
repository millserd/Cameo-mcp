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

    private CameoInfo() {
    }

    public static boolean hasProject() {
        try {
            return Application.getInstance().getProject() != null;
        } catch (Throwable t) {
            return false;
        }
    }

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

    private static void put(Map<String, Object> m, String key, String value) {
        if (value != null && !value.isEmpty()) {
            m.put(key, value);
        }
    }

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
