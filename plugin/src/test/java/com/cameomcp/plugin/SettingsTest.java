// family: server
package com.cameomcp.plugin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {

    @Test
    void overriddenTokenIsNotSaved() throws Exception {
        Path home = Files.createTempDirectory("cameo-mcp-settings");
        String oldHome = System.getProperty("cameo.mcp.home");
        System.setProperty("cameo.mcp.home", home.toString());
        System.setProperty("cameo.mcp.token", "from-jvm");
        try {
            Settings s = Settings.load();
            assertEquals("from-jvm", s.token);
            s.guiLogLevel = "all";
            s.save();
            String text = Files.readString(Settings.file().toPath());
            assertFalse(text.contains("from-jvm"));
            assertTrue(text.contains("guiLogLevel=all"));
            System.clearProperty("cameo.mcp.token");
            assertEquals("", Settings.load().token);
        } finally {
            System.clearProperty("cameo.mcp.token");
            if (oldHome == null) {
                System.clearProperty("cameo.mcp.home");
            } else {
                System.setProperty("cameo.mcp.home", oldHome);
            }
        }
    }
}
