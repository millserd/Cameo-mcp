package com.cameomcp.catalog;

import com.cameomcp.mcp.ToolSpec;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders the agent guide and a Markdown tool reference from the catalog. */
public final class CatalogDocs {

    /** Static methods only. */
    private CatalogDocs() {
    }

    /**
     * Writes the Markdown tool reference to the given file (used by the build to refresh docs/TOOLS.md).
     *
     * @param args optional output file path; with none, the reference is printed to standard output
     * @throws Exception if the catalog cannot be loaded or the file cannot be written
     */
    public static void main(String[] args) throws Exception {
        String text = toolReference(Catalog.all());
        if (args.length == 0) {
            System.out.print(text);
        } else {
            java.nio.file.Files.write(java.nio.file.Paths.get(args[0]), text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * The bundled AGENT-GUIDE.md (docs/AGENT-GUIDE.md in the source tree).
     *
     * @return the guide text; a short fallback built from Catalog.INSTRUCTIONS if the resource is missing or
     *     unreadable
     */
    public static String guide() {
        try (InputStream in = CatalogDocs.class.getResourceAsStream("/com/cameomcp/AGENT-GUIDE.md")) {
            if (in == null) {
                return "# Cameo MCP\n\nThe agent guide is not bundled in this build. " + Catalog.INSTRUCTIONS;
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "# Cameo MCP\n\n" + Catalog.INSTRUCTIONS;
        }
    }

    /**
     * Markdown reference of the given tools, grouped by family.
     *
     * @param specs the tools to document
     * @return Markdown with one section per family and, per tool, its kind, description and parameter table
     */
    @SuppressWarnings("unchecked")
    public static String toolReference(Collection<ToolSpec> specs) {
        Map<String, StringBuilder> byFamily = new LinkedHashMap<>();
        for (ToolSpec s : specs) {
            StringBuilder sb = byFamily.computeIfAbsent(s.family(), f -> new StringBuilder());
            sb.append("### `").append(s.name()).append("`");
            // Kind label after the name, e.g. "(read)" or "(side-effect)".
            sb.append(" (").append(s.kind().name().toLowerCase().replace('_', '-')).append(")\n\n");
            sb.append(s.description()).append("\n\n");
            if (!s.parameters().isEmpty()) {
                sb.append("| Parameter | Type | Required | Description |\n|---|---|---|---|\n");
                List<String> req = s.requiredParameters();
                for (Map.Entry<String, Object> e : s.parameters().entrySet()) {
                    Map<String, Object> p = (Map<String, Object>) e.getValue();
                    String type = p.get("type") == null ? "any" : String.valueOf(p.get("type"));
                    if ("array".equals(type) && p.get("items") instanceof Map) {
                        Object it = ((Map<String, Object>) p.get("items")).get("type");
                        type = (it == null ? "any" : it) + "[]";
                    }
                    String desc = p.get("description") == null ? "" : String.valueOf(p.get("description"));
                    if (p.get("enum") != null) {
                        desc += " One of: " + p.get("enum") + ".";
                    }
                    if (p.containsKey("default")) {
                        desc += " Default: `" + p.get("default") + "`.";
                    }
                    sb.append("| `").append(e.getKey()).append("` | ").append(type).append(" | ")
                            .append(req.contains(e.getKey()) ? "yes" : "").append(" | ")
                            // Pipes and newlines would break the Markdown table row.
                            .append(desc.replace("|", "\\|").replace("\n", " ")).append(" |\n");
                }
                sb.append('\n');
            }
        }
        StringBuilder out = new StringBuilder("# Cameo MCP tool reference\n\n");
        out.append(specs.size()).append(" tools.\n\n");
        for (Map.Entry<String, StringBuilder> e : byFamily.entrySet()) {
            out.append("## ").append(e.getKey()).append("\n\n").append(e.getValue());
        }
        return out.toString();
    }
}
