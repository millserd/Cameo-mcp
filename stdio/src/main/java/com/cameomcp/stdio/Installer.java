package com.cameomcp.stdio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Installs or removes the plugin folder in one or more Cameo installations, so installing needs
 * nothing but Java (Cameo's own bundled Java works):
 * <pre>
 * java -jar cameo-mcp-stdio.jar --install [--cameo-home DIR]... [--all]
 * java -jar cameo-mcp-stdio.jar --uninstall [--cameo-home DIR]... [--all]
 * </pre>
 * Without --cameo-home it searches the usual install locations and asks when it finds several.
 */
final class Installer {

    /** The plugin folder name under {@code <cameo>/plugins}; must match the id in plugin.xml. */
    static final String PLUGIN_ID = "com.cameomcp.plugin";

    /**
     * Folder names Cameo-family products install under (case-insensitive), used to limit the search
     * to likely product folders instead of scanning every directory.
     */
    private static final Pattern PRODUCT = Pattern.compile(
            "(?i).*(cameo|catia ?magic|magic ?systems|magicdraw|magic ?cyber|magic ?software|no[ _]?magic|dassault).*");

    /** Static methods only. */
    private Installer() {
    }

    /**
     * Runs the installer with the options after --install/--uninstall; returns the exit status.
     *
     * @param uninstall true to remove the plugin folder, false to install (replace) it
     * @param homes Cameo installation folders given with --cameo-home; empty means search
     * @param all with no --cameo-home, act on every installation found instead of asking
     * @param source the plugin folder to copy (holding plugin.xml); ignored when uninstalling
     * @param out where progress and errors are printed
     * @return 0 on success; 1 if nothing was found or chosen, a target was not a Cameo installation, or a
     *     copy or delete failed
     */
    static int run(boolean uninstall, List<String> homes, boolean all, Path source, PrintStream out) {
        if (!uninstall) {
            if (source == null || !Files.isRegularFile(source.resolve("plugin.xml"))) {
                out.println("Plugin files not found" + (source == null ? "" : " at " + source)
                        + ". Run this from the unzipped cameo-mcp package, or pass --from <plugin folder>.");
                return 1;
            }
        }
        List<Path> targets = new ArrayList<>();
        for (String h : homes) {
            targets.add(Paths.get(h));
        }
        if (targets.isEmpty()) {
            List<Path> found = findHomes();
            if (found.isEmpty()) {
                out.println("No Cameo installation found. Pass --cameo-home <folder containing plugins and lib>.");
                return 1;
            }
            if (found.size() == 1 || all) {
                targets.addAll(found);
            } else {
                Path chosen = null;
                out.println("Several Cameo installations were found:");
                for (int i = 0; i < found.size(); i++) {
                    out.println("  [" + (i + 1) + "] " + found.get(i));
                }
                out.print("Install into which one? Number, or A for all: ");
                out.flush();
                String answer = readLine();
                if (answer.equalsIgnoreCase("a")) {
                    targets.addAll(found);
                } else {
                    try {
                        chosen = found.get(Integer.parseInt(answer) - 1);
                    } catch (RuntimeException e) {
                        out.println("No installation chosen.");
                        return 1;
                    }
                    targets.add(chosen);
                }
            }
        }
        int status = 0;
        // Each target is replaced wholesale: the old plugin folder is deleted, then the new one copied.
        // A failure part-way stops at once and leaves that target as it is (possibly half-copied).
        for (Path cameo : targets) {
            if (!isCameo(cameo)) {
                out.println("Skipping " + cameo + ": not a Cameo installation (no plugins and lib folders).");
                status = 1;
                continue;
            }
            Path target = cameo.resolve("plugins").resolve(PLUGIN_ID);
            try {
                deleteTree(target);
                if (uninstall) {
                    out.println("Removed Cameo MCP from " + cameo);
                } else {
                    copyTree(source, target);
                    out.println("Installed Cameo MCP into " + target);
                }
            } catch (AccessDeniedException e) {
                out.println("No write access to " + cameo.resolve("plugins")
                        + ". Run again as administrator (Windows) or with sudo (macOS/Linux).");
                return 1;
            } catch (IOException e) {
                out.println("Failed on " + cameo + ": " + e);
                return 1;
            }
        }
        if (status != 0) {
            return status;
        }
        if (uninstall) {
            out.println("Done. Settings remain in ~/.cameo-mcp (delete that folder to remove them).");
        } else {
            out.println();
            out.println("Next steps:");
            out.println("  1. Start Cameo. An MCP menu appears; the Notification window shows \"[Cameo MCP] MCP server started\".");
            out.println("  2. MCP > MCP Server Status > Test connection.");
            out.println("  3. Point your MCP client at http://127.0.0.1:18740/mcp (the 'Connect a client' tab has ready-made configs).");
        }
        return 0;
    }

    /**
     * The plugin folder to install: the folder holding this jar when it sits inside the plugin
     * folder, else {@code ../plugin/com.cameomcp.plugin} next to the release zip's launcher folder.
     *
     * @return the plugin folder, or null when the jar location cannot be determined
     */
    static Path defaultSource() {
        try {
            Path jar = Paths.get(Installer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(jar) ? jar : jar.getParent();
            if (dir == null) {
                return null;
            }
            if (Files.isRegularFile(dir.resolve("plugin.xml"))) {
                return dir;
            }
            Path parent = dir.getParent();
            return parent == null ? null : parent.resolve("plugin").resolve(PLUGIN_ID);
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Tells whether a folder looks like a Cameo installation root.
     *
     * @param dir candidate folder
     * @return true if it has both a plugins and a lib folder
     */
    static boolean isCameo(Path dir) {
        return Files.isDirectory(dir.resolve("plugins")) && Files.isDirectory(dir.resolve("lib"));
    }

    /**
     * Cameo installations under the usual roots, one or two levels down, sorted and de-duplicated.
     *
     * @return the installation roots found, possibly empty
     */
    static List<Path> findHomes() {
        Set<Path> found = new LinkedHashSet<>();
        for (Path root : searchRoots()) {
            for (Path d : children(root)) {
                if (!PRODUCT.matcher(d.getFileName().toString()).matches()) {
                    continue;
                }
                if (isCameo(d)) {
                    found.add(d);
                }
                List<Path> subs = children(d);
                subs.add(d.resolve("Contents").resolve("Resources"));
                subs.add(d.resolve("Contents").resolve("Java"));
                for (Path s : subs) {
                    if (isCameo(s)) {
                        found.add(s);
                    }
                }
            }
        }
        List<Path> out = new ArrayList<>(found);
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /**
     * The folders searched for installations: Program Files, LOCALAPPDATA\Programs and the C: and D:
     * drive roots on Windows; /Applications, ~/Applications, /opt and /usr/local elsewhere; plus the
     * home folder and ~/Exe (where no-install builds are often unpacked) everywhere.
     *
     * @return the search roots; some may not exist
     */
    private static List<Path> searchRoots() {
        List<Path> roots = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            for (String env : new String[] {"ProgramFiles", "ProgramFiles(x86)"}) {
                String v = System.getenv(env);
                if (v != null) {
                    roots.add(Paths.get(v));
                }
            }
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) {
                roots.add(Paths.get(local, "Programs"));
            }
            roots.add(Paths.get("C:\\"));
            roots.add(Paths.get("D:\\"));
        } else {
            roots.add(Paths.get("/Applications"));
            roots.add(Paths.get(home, "Applications"));
            roots.add(Paths.get("/opt"));
            roots.add(Paths.get("/usr/local"));
        }
        if (!home.isEmpty()) {
            roots.add(Paths.get(home));
            roots.add(Paths.get(home, "Exe"));
        }
        return roots;
    }

    /**
     * Lists the immediate subdirectories of a folder.
     *
     * @param dir the folder
     * @return its subdirectories; empty when dir is missing or unreadable
     */
    private static List<Path> children(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path p : ds) {
                out.add(p);
            }
        } catch (IOException | RuntimeException ignored) {
            // unreadable folders are skipped
        }
        return out;
    }

    /**
     * Deletes a folder and everything in it, deepest entries first. Does nothing if it does not exist.
     *
     * @param dir the folder to delete
     * @throws IOException if any entry cannot be deleted
     */
    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> all = new ArrayList<>();
            walk.forEach(all::add);
            all.sort(Comparator.reverseOrder());
            for (Path p : all) {
                Files.delete(p);
            }
        }
    }

    /**
     * Copies a folder tree, replacing files that already exist.
     *
     * @param from source folder
     * @param to destination folder, created as needed
     * @throws IOException if any entry cannot be copied
     */
    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            List<Path> all = new ArrayList<>();
            walk.forEach(all::add);
            for (Path p : all) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Reads one answer line from standard input.
     *
     * @return the trimmed line, or an empty string at end of input or on error
     */
    private static String readLine() {
        try {
            String s = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            return s == null ? "" : s.trim();
        } catch (IOException e) {
            return "";
        }
    }
}
