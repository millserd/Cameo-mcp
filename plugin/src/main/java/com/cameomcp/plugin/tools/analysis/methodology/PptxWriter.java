package com.cameomcp.plugin.tools.analysis.methodology;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Minimal PresentationML (OOXML) writer: a 10in x 7.5in deck with a title slide followed by one
 * slide per image (picture fitted to the slide, aspect ratio kept) with a caption text box naming
 * the diagram. Self-contained: one slide master, one blank layout, one theme.
 */
public final class PptxWriter {

    // Slide size in EMUs (English Metric Units, 914400 per inch): 10in x 7.5in, the 4:3 "screen4x3" size.
    static final long SLIDE_CX = 9144000L;
    static final long SLIDE_CY = 6858000L;
    static final long EMU_PER_INCH = 914400L;

    private static final String NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    private static final String CT = "application/vnd.openxmlformats-officedocument.presentationml.";
    private static final String XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    private static final String PNS = "xmlns:a=\"" + NS_A + "\" xmlns:r=\"" + NS_R + "\" xmlns:p=\"" + NS_P + "\"";

    private PptxWriter() {
    }

    /** Writes the deck to {@code output}, replacing any existing file. */
    public static void write(List<OfficeImages.Image> images, Path output, String title) throws IOException {
        Files.write(output, toBytes(images, title));
    }

    /*
     * Builds the .pptx zip in memory: package parts, presentation, one master/layout/theme, a title slide
     * and one picture slide per image. Unlike PdfWriter, an empty image list is allowed (title slide only).
     */
    public static byte[] toBytes(List<OfficeImages.Image> images, String title) throws IOException {
        int slideCount = images.size() + 1;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            // [Content_Types].xml and _rels/.rels are the OPC package entry points PowerPoint reads first.
            put(zip, "[Content_Types].xml", contentTypes(slideCount));
            put(zip, "_rels/.rels", XML_DECL
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + REL + "officeDocument\" Target=\"ppt/presentation.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                    + "<Relationship Id=\"rId3\" Type=\"" + REL + "extended-properties\" Target=\"docProps/app.xml\"/>"
                    + "</Relationships>");
            put(zip, "docProps/app.xml", XML_DECL
                    + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">"
                    + "<Application>Cameo MCP</Application><Slides>" + slideCount + "</Slides></Properties>");
            String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
            put(zip, "docProps/core.xml", XML_DECL
                    + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                    + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\""
                    + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                    + "<dc:title>" + esc(title) + "</dc:title><dc:creator>Cameo MCP</dc:creator>"
                    + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:created>"
                    + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:modified>"
                    + "</cp:coreProperties>");
            put(zip, "ppt/presentation.xml", presentation(slideCount));
            put(zip, "ppt/_rels/presentation.xml.rels", presentationRels(slideCount));
            put(zip, "ppt/presProps.xml", XML_DECL + "<p:presentationPr " + PNS + "/>");
            put(zip, "ppt/viewProps.xml", XML_DECL + "<p:viewPr " + PNS + "><p:gridSpacing cx=\"76200\" cy=\"76200\"/></p:viewPr>");
            put(zip, "ppt/tableStyles.xml", XML_DECL + "<a:tblStyleLst xmlns:a=\"" + NS_A
                    + "\" def=\"{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}\"/>");
            put(zip, "ppt/slideMasters/slideMaster1.xml", slideMaster());
            put(zip, "ppt/slideMasters/_rels/slideMaster1.xml.rels", rels(
                    rel("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml"),
                    rel("rId2", "theme", "../theme/theme1.xml")));
            put(zip, "ppt/slideLayouts/slideLayout1.xml", slideLayout());
            put(zip, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", rels(
                    rel("rId1", "slideMaster", "../slideMasters/slideMaster1.xml")));
            put(zip, "ppt/theme/theme1.xml", theme());

            put(zip, "ppt/slides/slide1.xml", slide(
                    textBox(2, "Title 1", EMU_PER_INCH / 2, 2 * EMU_PER_INCH, SLIDE_CX - EMU_PER_INCH, 1500000L, title, 4000, true)
                            + textBox(3, "Subtitle 2", EMU_PER_INCH / 2, 3700000L, SLIDE_CX - EMU_PER_INCH, 900000L,
                            "Generated by Cameo MCP methodology workflow", 2000, false)));
            put(zip, "ppt/slides/_rels/slide1.xml.rels", rels(rel("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml")));

            // Image slides start at slide2; each embeds its PNG as rId2 and draws a caption from the file stem.
            for (int i = 0; i < images.size(); i++) {
                OfficeImages.Image image = images.get(i);
                int slideNo = i + 2;
                int imageNo = i + 1;
                put(zip, "ppt/media/image" + imageNo + ".png", image.png());
                put(zip, "ppt/slides/slide" + slideNo + ".xml", slide(picture(image)
                        + textBox(3, "TextBox 2", (long) (0.2 * EMU_PER_INCH), (long) (0.1 * EMU_PER_INCH),
                        (long) (9.4 * EMU_PER_INCH), (long) (0.4 * EMU_PER_INCH),
                        OfficeImages.titleCase(image.stem().replace("-", " ")), 1800, false)));
                put(zip, "ppt/slides/_rels/slide" + slideNo + ".xml.rels", rels(
                        rel("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml"),
                        rel("rId2", "image", "../media/image" + imageNo + ".png")));
            }
        }
        return bytes.toByteArray();
    }

    // ------------------------------------------------------------------ parts

    /** OPC content types: defaults by extension plus an override for every XML part, including each slide. */
    private static String contentTypes(int slideCount) {
        StringBuilder sb = new StringBuilder(XML_DECL);
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        sb.append("<Default Extension=\"png\" ContentType=\"image/png\"/>");
        override(sb, "/ppt/presentation.xml", CT + "presentation.main+xml");
        override(sb, "/ppt/presProps.xml", CT + "presProps+xml");
        override(sb, "/ppt/viewProps.xml", CT + "viewProps+xml");
        override(sb, "/ppt/tableStyles.xml", CT + "tableStyles+xml");
        override(sb, "/ppt/slideMasters/slideMaster1.xml", CT + "slideMaster+xml");
        override(sb, "/ppt/slideLayouts/slideLayout1.xml", CT + "slideLayout+xml");
        override(sb, "/ppt/theme/theme1.xml", "application/vnd.openxmlformats-officedocument.theme+xml");
        override(sb, "/docProps/core.xml", "application/vnd.openxmlformats-package.core-properties+xml");
        override(sb, "/docProps/app.xml", "application/vnd.openxmlformats-officedocument.extended-properties+xml");
        for (int i = 1; i <= slideCount; i++) {
            override(sb, "/ppt/slides/slide" + i + ".xml", CT + "slide+xml");
        }
        return sb.append("</Types>").toString();
    }

    private static void override(StringBuilder sb, String part, String type) {
        sb.append("<Override PartName=\"").append(part).append("\" ContentType=\"").append(type).append("\"/>");
    }

    /*
     * presentation.xml: slide IDs must be at least 256 (hence 255 + i) and master IDs at least 2^31.
     * Relationship rId1 is the master, rId2.. the slides, matching presentationRels().
     */
    private static String presentation(int slideCount) {
        StringBuilder sb = new StringBuilder(XML_DECL);
        sb.append("<p:presentation ").append(PNS).append(" saveSubsetFonts=\"1\">");
        sb.append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>");
        sb.append("<p:sldIdLst>");
        for (int i = 1; i <= slideCount; i++) {
            sb.append("<p:sldId id=\"").append(255 + i).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        sb.append("</p:sldIdLst>");
        sb.append("<p:sldSz cx=\"").append(SLIDE_CX).append("\" cy=\"").append(SLIDE_CY).append("\" type=\"screen4x3\"/>");
        sb.append("<p:notesSz cx=\"6858000\" cy=\"9144000\"/>");
        sb.append("<p:defaultTextStyle><a:defPPr><a:defRPr lang=\"en-US\"/></a:defPPr></p:defaultTextStyle>");
        return sb.append("</p:presentation>").toString();
    }

    // Relationship IDs after the slides continue from slideCount + 2 so they never collide with slide rIds.
    private static String presentationRels(int slideCount) {
        StringBuilder sb = new StringBuilder();
        sb.append(rel("rId1", "slideMaster", "slideMasters/slideMaster1.xml"));
        for (int i = 1; i <= slideCount; i++) {
            sb.append(rel("rId" + (i + 1), "slide", "slides/slide" + i + ".xml"));
        }
        int next = slideCount + 2;
        sb.append(rel("rId" + next, "presProps", "presProps.xml"));
        sb.append(rel("rId" + (next + 1), "viewProps", "viewProps.xml"));
        sb.append(rel("rId" + (next + 2), "theme", "theme/theme1.xml"));
        sb.append(rel("rId" + (next + 3), "tableStyles", "tableStyles.xml"));
        return rels(sb.toString());
    }

    // Group-shape header every spTree needs; callers append their shapes and close </p:spTree>.
    private static final String EMPTY_TREE = "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/>"
            + "</p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>"
            + "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>";

    private static String slideMaster() {
        return XML_DECL + "<p:sldMaster " + PNS + "><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/>"
                + "</p:bgRef></p:bg>" + EMPTY_TREE + "</p:spTree></p:cSld>"
                + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\""
                + " accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\""
                + " folHlink=\"folHlink\"/>"
                + "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>"
                + "<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz=\"4400\"/></a:lvl1pPr></p:titleStyle>"
                + "<p:bodyStyle><a:lvl1pPr><a:defRPr sz=\"2000\"/></a:lvl1pPr></p:bodyStyle>"
                + "<p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:otherStyle></p:txStyles>"
                + "</p:sldMaster>";
    }

    private static String slideLayout() {
        return XML_DECL + "<p:sldLayout " + PNS + " type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\">"
                + EMPTY_TREE + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>";
    }

    private static String slide(String shapes) {
        return XML_DECL + "<p:sld " + PNS + "><p:cSld>" + EMPTY_TREE + shapes + "</p:spTree></p:cSld>"
                + "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>";
    }

    /** Picture shape scaled to fit the slide while keeping aspect ratio, centered; references the slide's rId2 image. */
    private static String picture(OfficeImages.Image image) {
        double scale = Math.min((double) SLIDE_CX / image.width(), (double) SLIDE_CY / image.height());
        long cx = Math.max(1L, Math.round(image.width() * scale));
        long cy = Math.max(1L, Math.round(image.height() * scale));
        long x = (SLIDE_CX - cx) / 2;
        long y = (SLIDE_CY - cy) / 2;
        return "<p:pic><p:nvPicPr><p:cNvPr id=\"2\" name=\"Picture 1\" descr=\"" + esc(image.stem()) + "\"/>"
                + "<p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>"
                + "<p:blipFill><a:blip r:embed=\"rId2\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>"
                + "<p:spPr><a:xfrm><a:off x=\"" + x + "\" y=\"" + y + "\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>";
    }

    /** Single-run text box at the given EMU position; {@code size} is in hundredths of a point. */
    private static String textBox(int id, String name, long x, long y, long cx, long cy, String text, int size,
                                  boolean bold) {
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"" + id + "\" name=\"" + esc(name) + "\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/>"
                + "</p:nvSpPr><p:spPr><a:xfrm><a:off x=\"" + x + "\" y=\"" + y + "\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy
                + "\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>"
                + "<p:txBody><a:bodyPr wrap=\"square\" rtlCol=\"0\"><a:normAutofit/></a:bodyPr><a:lstStyle/>"
                + "<a:p><a:r><a:rPr lang=\"en-US\" sz=\"" + size + "\"" + (bold ? " b=\"1\"" : "") + " dirty=\"0\"/>"
                + "<a:t>" + esc(text) + "</a:t></a:r></a:p></p:txBody></p:sp>";
    }

    /** Minimal standard Office theme (colors, Calibri fonts, plain format styles); PowerPoint requires a theme part. */
    private static String theme() {
        StringBuilder sb = new StringBuilder(XML_DECL);
        sb.append("<a:theme xmlns:a=\"").append(NS_A).append("\" name=\"Office Theme\"><a:themeElements>");
        sb.append("<a:clrScheme name=\"Office\">");
        sb.append("<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>");
        sb.append("<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>");
        String[][] colors = {{"dk2", "44546A"}, {"lt2", "E7E6E6"}, {"accent1", "4472C4"}, {"accent2", "ED7D31"},
                {"accent3", "A5A5A5"}, {"accent4", "FFC000"}, {"accent5", "5B9BD5"}, {"accent6", "70AD47"},
                {"hlink", "0563C1"}, {"folHlink", "954F72"}};
        for (String[] c : colors) {
            sb.append("<a:").append(c[0]).append("><a:srgbClr val=\"").append(c[1]).append("\"/></a:").append(c[0]).append('>');
        }
        sb.append("</a:clrScheme>");
        sb.append("<a:fontScheme name=\"Office\">");
        sb.append("<a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>");
        sb.append("<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont>");
        sb.append("</a:fontScheme>");
        sb.append("<a:fmtScheme name=\"Office\"><a:fillStyleLst>");
        sb.append("<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>".repeat(3));
        sb.append("</a:fillStyleLst><a:lnStyleLst>");
        for (int w : new int[]{6350, 12700, 19050}) {
            sb.append("<a:ln w=\"").append(w).append("\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill>")
                    .append("<a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/><a:miter lim=\"800000\"/></a:ln>");
        }
        sb.append("</a:lnStyleLst><a:effectStyleLst>");
        sb.append("<a:effectStyle><a:effectLst/></a:effectStyle>".repeat(3));
        sb.append("</a:effectStyleLst><a:bgFillStyleLst>");
        sb.append("<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>".repeat(3));
        sb.append("</a:bgFillStyleLst></a:fmtScheme>");
        sb.append("</a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>");
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static String rel(String id, String type, String target) {
        return "<Relationship Id=\"" + id + "\" Type=\"" + REL + type + "\" Target=\"" + target + "\"/>";
    }

    private static String rels(String... relationships) {
        return XML_DECL + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + String.join("", relationships) + "</Relationships>";
    }

    /** XML escape for text and attribute values; control characters invalid in XML 1.0 become spaces. */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                        sb.append(' ');
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        put(zip, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
