package com.cameomcp.stdio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallerTest {

    @Test
    void installsReplacesAndUninstalls(@TempDir Path tmp) throws Exception {
        Path source = tmp.resolve("pkg").resolve(Installer.PLUGIN_ID);
        Files.createDirectories(source.resolve("lib"));
        Files.writeString(source.resolve("plugin.xml"), "<plugin/>");
        Files.writeString(source.resolve("lib").resolve("a.txt"), "a");
        Path cameo = tmp.resolve("Cameo");
        Files.createDirectories(cameo.resolve("plugins"));
        Files.createDirectories(cameo.resolve("lib"));
        Path target = cameo.resolve("plugins").resolve(Installer.PLUGIN_ID);
        Files.createDirectories(target);
        Files.writeString(target.resolve("stale.jar"), "old");

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        assertEquals(0, Installer.run(false, Collections.singletonList(cameo.toString()), false, source, out));
        assertTrue(Files.isRegularFile(target.resolve("plugin.xml")));
        assertTrue(Files.isRegularFile(target.resolve("lib").resolve("a.txt")));
        assertFalse(Files.exists(target.resolve("stale.jar")), "old files are removed before copying");

        assertEquals(0, Installer.run(true, Collections.singletonList(cameo.toString()), false, null, out));
        assertFalse(Files.exists(target));
        assertTrue(Files.isDirectory(cameo.resolve("plugins")));
    }

    @Test
    void refusesFoldersThatAreNotCameo(@TempDir Path tmp) throws Exception {
        Path source = tmp.resolve(Installer.PLUGIN_ID);
        Files.createDirectories(source);
        Files.writeString(source.resolve("plugin.xml"), "<plugin/>");
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(1, Installer.run(false, Collections.singletonList(tmp.resolve("nope").toString()), false,
                source, out));
        assertEquals(1, Installer.run(false, Collections.singletonList(tmp.toString()), false,
                tmp.resolve("missing"), out));
    }
}
