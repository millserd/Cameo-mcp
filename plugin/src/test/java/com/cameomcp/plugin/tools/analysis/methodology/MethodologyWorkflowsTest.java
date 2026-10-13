// family: analysis
package com.cameomcp.plugin.tools.analysis.methodology;

import com.cameomcp.mcp.ToolException;
import com.cameomcp.plugin.tools.analysis.FakeBridge;
import com.cameomcp.plugin.tools.analysis.J;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.cameomcp.plugin.tools.analysis.Fixtures.solidPngBase64;
import static com.cameomcp.plugin.tools.analysis.J.arr;
import static com.cameomcp.plugin.tools.analysis.J.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodologyWorkflowsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object o) {
        return (Map<String, Object>) o;
    }

    private static FakeBridge imageBridge() {
        FakeBridge b = new FakeBridge();
        b.imageFn = id -> obj("id", id, "name", "Logical IBD", "format", "png", "width", 20, "height", 10,
                "image", solidPngBase64(20, 10, new Color(0, 128, 255)));
        return b;
    }

    private static List<Object> currentIbd() {
        return arr(obj("key", "logical_ibd", "kind", "ibd", "name", "Logical IBD", "element_id", "dia-1"));
    }

    private static List<Object> expectedIbd() {
        return arr(obj("key", "logical_ibd", "kind", "ibd", "name", "Logical IBD"));
    }

    @Test
    void compareAgainstExpectedArtifactListReturnsPatchPlan() {
        Map<String, Object> result = MethodologyWorkflows.compareAgainstExpectedArtifactList(
                arr(obj("key", "workspace", "kind", "Package", "name", "Workspace"),
                        obj("key", "logical_ibd", "kind", "ibd", "name", "Logical IBD")),
                arr(obj("key", "workspace", "kind", "Package", "name", "Workspace", "element_id", "pkg-1")));
        assertEquals(false, result.get("ready"));
        assertEquals(List.of("logical_ibd"), result.get("missingArtifactKeys"));
        assertTrue(J.list(result.get("patchPlan")).size() > 0);
    }

    @Test
    void compareIsReadyWhenEverythingMatches() {
        Map<String, Object> result = MethodologyWorkflows.compareAgainstExpectedArtifactList(expectedIbd(), currentIbd());
        assertEquals(true, result.get("ready"));
        assertEquals(List.of(), result.get("missingArtifactKeys"));
    }

    @Test
    void validateMethodologyPackageUsesCustomExpectedArtifacts() {
        Map<String, Object> result = MethodologyWorkflows.validateMethodologyPackage("oosem", null,
                arr(obj("key", "workspace", "kind", "Package", "name", "Workspace", "element_id", "pkg-1")),
                arr(obj("key", "workspace", "kind", "Package", "name", "Workspace"),
                        obj("key", "logical_ibd", "kind", "ibd", "name", "Logical IBD")));
        assertEquals(false, result.get("ready"));
        assertTrue(result.containsKey("artifactComparison"));
        assertTrue(result.containsKey("recommendedActions"));
        assertEquals("pack", result.get("scope"));
    }

    @Test
    void buildExpectedArtifactsCoversPackRecipes() {
        List<Map<String, Object>> expected = MethodologyWorkflows.buildExpectedArtifacts("oosem", null);
        assertFalse(expected.isEmpty());
        for (Map<String, Object> artifact : expected) {
            assertTrue(J.truthy(artifact.get("key")));
            assertTrue(J.truthy(artifact.get("kind")));
            Object name = artifact.get("name");
            assertTrue(name == null || !J.str(name).contains("<"), "placeholder leaked into name: " + name);
        }
        List<Map<String, Object>> scoped = MethodologyWorkflows.buildExpectedArtifacts("oosem", "use_case_model");
        assertTrue(scoped.size() < expected.size());
        for (Map<String, Object> artifact : scoped) {
            assertEquals("use_case_model", artifact.get("recipe_id"));
        }
    }

    @Test
    void exportRequiredDiagramsDryRunPlansFiles() {
        Map<String, Object> result = MethodologyWorkflows.exportRequiredDiagrams("oosem", null, currentIbd(),
                expectedIbd(), ".png");
        assertEquals(true, result.get("dryRun"));
        assertEquals(true, result.get("readyToExport"));
        Map<String, Object> item = J.maps(result.get("exportItems")).get(0);
        assertEquals("logical-ibd.png", item.get("outputFile"));
        assertEquals("dia-1", item.get("diagramId"));
        assertEquals("png", result.get("exportFormat"));
    }

    @Test
    void exportBlocksMissingDiagrams() {
        Map<String, Object> result = MethodologyWorkflows.exportRequiredDiagrams("oosem", null, arr(), expectedIbd(), "png");
        assertEquals(false, result.get("readyToExport"));
        assertEquals(List.of("logical_ibd"), result.get("blockedDiagramKeys"));
        assertNull(J.maps(result.get("exportItems")).get(0).get("diagramId"));
    }

    @Test
    void assemblePptPdfDryRunPlansSlides() {
        Map<String, Object> result = MethodologyWorkflows.assemblePptPdf("oosem", null, currentIbd(), expectedIbd(),
                null, "Demo Deck", null, null, "png");
        Map<String, Object> plan = m(result.get("presentationPlan"));
        assertEquals("demo-deck.pptx", plan.get("pptxFile"));
        assertEquals("demo-deck.pdf", plan.get("pdfFile"));
        assertEquals(3, ((Number) plan.get("slideCount")).intValue());
        assertEquals(true, plan.get("readyToAssemble"));
        assertEquals(true, result.get("dryRun"));
    }

    @Test
    void exportRequiredDiagramsLiveWritesImages(@TempDir Path tmp) throws Exception {
        FakeBridge bridge = imageBridge();
        Map<String, Object> result = MethodologyWorkflows.exportRequiredDiagramsLive("oosem", null, null,
                currentIbd(), expectedIbd(), "png", tmp.toString(), bridge);
        assertEquals(false, result.get("dryRun"));
        assertEquals(1, ((Number) result.get("exportedCount")).intValue());
        Path exported = Paths.get(J.str(J.maps(result.get("exports")).get(0).get("outputFile")));
        assertTrue(Files.exists(exported));
        assertTrue(Files.size(exported) > 0);
    }

    @Test
    void assemblePptPdfLiveCreatesFiles(@TempDir Path tmp) throws Exception {
        FakeBridge bridge = imageBridge();
        Map<String, Object> result = MethodologyWorkflows.assemblePptPdfLive("oosem", null, null, currentIbd(),
                expectedIbd(), tmp.toString(), "Demo Deck", null, null, "png", bridge);
        assertEquals(false, result.get("dryRun"));
        Map<String, Path> created = new HashMap<>();
        for (Map<String, Object> item : J.maps(result.get("createdFiles"))) {
            created.put(J.str(item.get("kind")), Paths.get(J.str(item.get("path"))));
        }
        assertTrue(Files.exists(created.get("pdf")));
        assertTrue(Files.exists(created.get("pptx")));
        assertTrue(Files.size(created.get("pptx")) > 0);
    }

    @Test
    void liveExportWithoutOutputDirStaysDryRun() throws Exception {
        Map<String, Object> result = MethodologyWorkflows.exportRequiredDiagramsLive("oosem", null, null,
                currentIbd(), expectedIbd(), "png", null, imageBridge());
        assertEquals(true, result.get("dryRun"));
    }

    @Test
    void missingImagePayloadFails() {
        ToolException e = assertThrows(ToolException.class,
                () -> MethodologyWorkflows.decodeExportedImage(obj("id", "d")));
        assertEquals("TOOL_FAILED", e.code());
    }
}
