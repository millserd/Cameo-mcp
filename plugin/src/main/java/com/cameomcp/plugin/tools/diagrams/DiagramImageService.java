package com.cameomcp.plugin.tools.diagrams;

import com.cameomcp.json.Json;
import com.cameomcp.mcp.Args;
import com.cameomcp.mcp.ToolContext;
import com.cameomcp.mcp.ToolException;
import com.cameomcp.mcp.ToolResult;
import com.cameomcp.plugin.cameo.Edt;
import com.nomagic.magicdraw.export.image.ImageExporter;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/** Diagram image export: inline MCP image content and export to a file. */
final class DiagramImageService {

    private DiagramImageService() {
    }

    // Shared scale_percentage argument of both image tools: 100 by default, 25-1000 allowed.
    static int scalePercentage(Args a) {
        Integer scale = a.integerOrNull("scale_percentage");
        if (scale == null) {
            return 100;
        }
        if (scale < 25 || scale > 1000) {
            throw ToolException.invalid("scale_percentage must be between 25 and 1000 (got " + scale + ").");
        }
        return scale;
    }

    // ------------------------------------------------------------------ inline image

    /*
     * cameo_get_diagram_image: renders the diagram with Cameo's ImageExporter and returns it as an MCP
     * image content block plus JSON metadata. Optional downscale (max_width/max_height) and JPEG/WebP
     * transcoding happen off the EDT in ImageTransform; include_image=false returns only the metadata.
     */
    static ToolResult getImage(Args a, ToolContext ctx) throws Exception {
        String diagramId = a.reqStr("diagram_id");
        boolean includeImage = a.bool("include_image", true);
        String format = ImageTransform.normalizeFormat(a.str("format", "png"));
        Integer maxWidth = positiveOrNull(a.integerOrNull("max_width"));
        Integer maxHeight = positiveOrNull(a.integerOrNull("max_height"));
        int quality = Math.max(1, Math.min(a.integer("quality", 85), 100));
        int scale = scalePercentage(a);

        // Rendering must happen on the EDT; 180 s limit because large diagrams at high scale render slowly.
        Object[] exported = Edt.onEdt("Export Diagram Image", project -> {
            DiagramPresentationElement dpe = DiagramSupport.loadedDiagram(project, diagramId);
            BufferedImage image = ImageExporter.export(dpe, false, scale);
            if (image == null) {
                throw new ToolException("TOOL_FAILED", "Cameo returned no image for diagram '" + diagramId
                        + "' (the diagram may be empty).");
            }
            return new Object[]{image, DiagramSupport.name(dpe)};
        }, 180);
        BufferedImage image = (BufferedImage) exported[0];

        Map<String, Object> meta = Json.obj(
                "id", diagramId,
                "name", exported[1],
                "format", "png",
                "width", image.getWidth(),
                "height", image.getHeight(),
                "scalePercentage", scale);

        boolean resize = maxWidth != null || maxHeight != null;
        boolean transcode = !"png".equals(format);
        ImageTransform.Encoded encoded;
        // No transform requested (or the image is not returned at all): plain PNG of the export.
        if (!includeImage || (!resize && !transcode)) {
            byte[] png = ImageTransform.png(image);
            encoded = new ImageTransform.Encoded(png, "png", "image/png", image.getWidth(), image.getHeight(), null);
        } else {
            encoded = ImageTransform.encode(image, format, maxWidth, maxHeight, quality);
        }

        if (!includeImage) {
            meta.put("imageBytes", encoded.bytes.length);
            meta.put("imageOmitted", true);
            meta.put("mimeType", "image/png");
            return ToolResult.of(meta);
        }
        meta.put("format", encoded.format);
        meta.put("mimeType", encoded.mimeType);
        meta.put("width", encoded.width);
        meta.put("height", encoded.height);
        if (encoded.width != image.getWidth() || encoded.height != image.getHeight()) {
            meta.put("originalWidth", image.getWidth());
            meta.put("originalHeight", image.getHeight());
        }
        meta.put("imageBytes", encoded.bytes.length);
        if (encoded.note != null) {
            meta.put("requestedFormat", "webp");
            meta.put("formatNote", encoded.note);
        }
        String base64 = Base64.getEncoder().encodeToString(encoded.bytes);
        meta.put("imageContent", "Returned as an MCP image content block (" + encoded.mimeType + ").");

        ToolResult result = new ToolResult();
        result.text(Json.write(meta));
        result.image(base64, encoded.mimeType);
        if (isInternal(ctx)) {
            // In-process callers (verification tools) only see structured content: keep the reference contract.
            Map<String, Object> structured = Json.copy(meta);
            structured.put("image", base64);
            result.structured(structured);
        } else {
            result.structured(meta);
        }
        return result;
    }

    // In-process calls (Internal.call) have no MCP session and the client name "internal".
    private static boolean isInternal(ToolContext ctx) {
        return ctx != null && ctx.sessionId() == null && "internal".equals(ctx.clientName());
    }

    private static Integer positiveOrNull(Integer v) {
        return v != null && v > 0 ? v : null;
    }

    // ------------------------------------------------------------------ export to file

    /*
     * cameo_export_diagram_image: writes the diagram to an absolute file path as PNG, JPEG or SVG via
     * ImageExporter. The format comes from the format argument or else the file extension.
     */
    static Map<String, Object> exportToFile(Args a) throws Exception {
        String diagramId = a.reqStr("diagram_id");
        String path = a.reqStr("file_path").trim();
        File file = new File(path);
        if (!file.isAbsolute()) {
            throw ToolException.invalid("file_path must be an absolute path (got '" + path + "').");
        }
        String format = a.optTrim("format");
        if (format == null) {
            String lower = path.toLowerCase(Locale.ROOT);
            format = lower.endsWith(".svg") ? "svg" : lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "jpeg" : "png";
        }
        format = format.toLowerCase(Locale.ROOT);
        if (format.equals("jpg")) {
            format = "jpeg";
        }
        String constant;
        String mime;
        switch (format) {
            case "png":
                constant = "PNG";
                mime = "image/png";
                break;
            case "jpeg":
                constant = "JPEG";
                mime = "image/jpeg";
                break;
            case "svg":
                constant = "SVG";
                mime = "image/svg+xml";
                break;
            default:
                throw ToolException.invalid("format must be one of: png, jpeg, jpg, svg (got '" + format + "').");
        }
        int scale = scalePercentage(a);
        Integer dpi = a.integerOrNull("dpi");
        if (dpi != null && (dpi < 36 || dpi > 1200)) {
            throw ToolException.invalid("dpi must be between 36 and 1200.");
        }
        boolean overwrite = a.bool("overwrite", true);
        if (file.isDirectory()) {
            throw ToolException.invalid("file_path '" + path + "' is a directory; give a file name such as diagram."
                    + format.replace("jpeg", "jpg") + ".");
        }
        if (file.exists() && !overwrite) {
            throw new ToolException("CONFLICT", "File '" + path + "' already exists. Pass overwrite=true to replace it.");
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new ToolException("TOOL_FAILED", "Cannot create directory '" + parent + "'.");
        }
        // Resolved before the EDT call so an unsupported format fails without touching the diagram.
        int formatCode = exporterConstant(constant);
        final String fmt = format;
        Object[] exported = Edt.onEdt("Export Diagram Image File", project -> {
            DiagramPresentationElement dpe = DiagramSupport.loadedDiagram(project, diagramId);
            Rectangle bounds;
            // The simple overload covers the default case; otherwise the extended one, with 72 dpi when only
            // scale was given.
            if (dpi == null && scale == 100) {
                bounds = ImageExporter.export(dpe, formatCode, file);
            } else {
                bounds = ImageExporter.export(dpe, formatCode, file, false, dpi != null ? dpi : 72, scale);
            }
            return new Object[]{bounds, DiagramSupport.name(dpe)};
        }, 300);
        // The exporter's return value alone does not prove a file was written, so check the file itself.
        if (!file.isFile() || file.length() == 0) {
            throw new ToolException("TOOL_FAILED", "Cameo did not write '" + path + "' (the diagram may be empty or "
                    + "the location is not writable).");
        }
        Rectangle bounds = (Rectangle) exported[0];
        Integer width = null;
        Integer height = null;
        // Report pixel size read back from the written raster file (it reflects scale/dpi); SVG and
        // unreadable files fall back to the diagram bounds.
        if (!"svg".equals(fmt)) {
            int[] dims = rasterSize(file);
            if (dims != null) {
                width = dims[0];
                height = dims[1];
            }
        }
        if (width == null && bounds != null) {
            width = bounds.width;
            height = bounds.height;
        }
        Map<String, Object> out = Json.obj(
                "diagramId", diagramId,
                "name", exported[1],
                "path", file.getAbsolutePath(),
                "format", fmt.equals("jpeg") ? "jpg" : fmt,
                "mimeType", mime,
                "bytes", file.length(),
                "width", width,
                "height", height,
                "scalePercentage", scale,
                "exported", true);
        if (dpi != null) {
            out.put("dpi", dpi);
        }
        if (bounds != null) {
            out.put("diagramBounds", Json.obj("x", bounds.x, "y", bounds.y, "width", bounds.width, "height", bounds.height));
        }
        return out;
    }

    /** Read ImageExporter's format constant at run time (stub constants must not be inlined). */
    private static int exporterConstant(String name) {
        try {
            return ImageExporter.class.getField(name).getInt(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new ToolException("UNSUPPORTED", "This Cameo version's ImageExporter has no " + name + " format.");
        }
    }

    // Width/height of the first image in a raster file via ImageIO, without decoding the pixels; null if unreadable.
    private static int[] rasterSize(File file) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
