// family: analysis
package com.cameomcp.plugin.tools.analysis.methodology;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficeWritersTest {

    private static OfficeImages.Image image(String stem, int w, int h, Color color) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return OfficeImages.fromBufferedImage(stem, img);
    }

    private static List<OfficeImages.Image> images() {
        return List.of(image("logical-ibd", 200, 100, Color.BLUE), image("use-case-diagram", 50, 120, Color.RED));
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                entries.put(e.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    @Test
    void pptxHasRequiredPartsAndWellFormedXml() throws Exception {
        Map<String, byte[]> entries = unzip(PptxWriter.toBytes(images(), "Demo & Deck"));
        for (String part : List.of("[Content_Types].xml", "_rels/.rels", "docProps/app.xml", "docProps/core.xml",
                "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels", "ppt/slideMasters/slideMaster1.xml",
                "ppt/slideLayouts/slideLayout1.xml", "ppt/theme/theme1.xml",
                "ppt/slides/slide1.xml", "ppt/slides/slide2.xml", "ppt/slides/slide3.xml",
                "ppt/slides/_rels/slide2.xml.rels", "ppt/media/image1.png", "ppt/media/image2.png")) {
            assertNotNull(entries.get(part), "missing part " + part);
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (e.getKey().endsWith(".xml") || e.getKey().endsWith(".rels")) {
                f.newDocumentBuilder().parse(new ByteArrayInputStream(e.getValue()));
            }
        }
        String contentTypes = new String(entries.get("[Content_Types].xml"), StandardCharsets.UTF_8);
        assertTrue(contentTypes.contains("/ppt/slides/slide3.xml"));
        String slide1 = new String(entries.get("ppt/slides/slide1.xml"), StandardCharsets.UTF_8);
        assertTrue(slide1.contains("Demo &amp; Deck"));
        String slide2 = new String(entries.get("ppt/slides/slide2.xml"), StandardCharsets.UTF_8);
        assertTrue(slide2.contains("Logical Ibd"));
        String rels = new String(entries.get("ppt/slides/_rels/slide2.xml.rels"), StandardCharsets.UTF_8);
        assertTrue(rels.contains("../media/image1.png"));
        BufferedImage media = ImageIO.read(new ByteArrayInputStream(entries.get("ppt/media/image1.png")));
        assertEquals(200, media.getWidth());
        assertEquals(100, media.getHeight());
        String presentation = new String(entries.get("ppt/presentation.xml"), StandardCharsets.UTF_8);
        assertTrue(presentation.contains("cx=\"9144000\""));
        assertEquals(3, Pattern.compile("<p:sldId ").matcher(presentation).results().count());
    }

    @Test
    void pdfHasValidXrefTable() throws Exception {
        byte[] pdf = PdfWriter.toBytes(images());
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("%PDF-1.4\n"));
        assertTrue(text.endsWith("%%EOF\n"));
        Matcher sx = Pattern.compile("startxref\n(\\d+)\n%%EOF\n$").matcher(text);
        assertTrue(sx.find());
        int xref = Integer.parseInt(sx.group(1));
        assertTrue(text.startsWith("xref\n", xref));
        Matcher header = Pattern.compile("xref\n0 (\\d+)\n").matcher(text);
        assertTrue(header.find(xref));
        int size = Integer.parseInt(header.group(1));
        assertEquals(2 + 2 * 3 + 1, size);
        Matcher entries = Pattern.compile("(\\d{10}) (\\d{5}) ([nf]) \n").matcher(text);
        int obj = 0;
        int pos = header.end();
        while (obj < size && entries.find(pos) && entries.start() == pos) {
            if (obj == 0) {
                assertEquals("f", entries.group(3));
            } else {
                int offset = Integer.parseInt(entries.group(1));
                assertTrue(text.startsWith(obj + " 0 obj\n", offset), "xref entry " + obj + " misaligned");
            }
            pos = entries.end();
            obj++;
        }
        assertEquals(size, obj);
        assertTrue(text.contains("/Size " + size + " /Root 1 0 R"));
        assertTrue(text.contains("/Count 2"));
        assertTrue(text.contains("/MediaBox [0 0 200 100]"));
    }

    @Test
    void pdfImageStreamsAreReadableJpeg() throws Exception {
        byte[] pdf = PdfWriter.toBytes(List.of(image("a", 30, 20, Color.GREEN)));
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("/Filter /DCTDecode /Length (\\d+) >>\nstream\n").matcher(text);
        assertTrue(m.find());
        int len = Integer.parseInt(m.group(1));
        byte[] jpeg = java.util.Arrays.copyOfRange(pdf, m.end(), m.end() + len);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertEquals(30, decoded.getWidth());
        assertTrue(text.startsWith("\nendstream", m.end() + len));
    }

    @Test
    void emptyImageListIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PdfWriter.toBytes(List.of()));
    }

    @Test
    void loadFlattensAlphaAndRejectsNonRaster(@TempDir Path tmp) throws Exception {
        BufferedImage transparent = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(transparent, "png", out);
        Path png = tmp.resolve("clear-view.png");
        Files.write(png, out.toByteArray());
        OfficeImages.Image loaded = OfficeImages.load(List.of(png)).get(0);
        assertEquals("clear-view", loaded.stem());
        assertEquals(0xFFFFFF, loaded.rgb().getRGB(0, 0) & 0xFFFFFF);

        Path svg = tmp.resolve("x.svg");
        Files.writeString(svg, "<svg/>");
        com.cameomcp.mcp.ToolException e = assertThrows(com.cameomcp.mcp.ToolException.class,
                () -> OfficeImages.load(List.of(svg)));
        assertEquals("TOOL_FAILED", e.code());
    }

    @Test
    void titleCaseMatchesPython() {
        assertEquals("Logical Ibd", OfficeImages.titleCase("logical ibd"));
        assertEquals("Use Case-Model 2Nd", OfficeImages.titleCase("use case-MODEL 2nd"));
        assertEquals("They'Re", OfficeImages.titleCase("they're"));
    }
}
