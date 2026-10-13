package com.cameomcp.plugin.tools.analysis.methodology;

import com.cameomcp.mcp.ToolException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Raster images loaded for PDF / PPTX assembly (alpha flattened onto white). */
public final class OfficeImages {

    private OfficeImages() {
    }

    /** One exported diagram image. */
    public record Image(String stem, int width, int height, BufferedImage rgb) {

        /** Lossless PNG encoding of the flattened RGB image (used for PPTX slides). */
        public byte[] png() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(rgb, "png", out);
            return out.toByteArray();
        }

        /** JPEG at the given quality (0..1); used for PDF pages, which embed images as DCTDecode streams. */
        public byte[] jpeg(float quality) throws IOException {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                throw new IOException("No JPEG encoder available in this Java runtime.");
            }
            ImageWriter writer = writers.next();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
                writer.write(null, new IIOImage(rgb, null, null), param);
            } finally {
                writer.dispose();
            }
            return out.toByteArray();
        }
    }

    /*
     * Copies the image into an opaque RGB buffer over a white background. JPEG cannot carry alpha
     * and ImageIO's JPEG writer does not handle alpha reliably, so transparency is flattened here.
     */
    public static Image fromBufferedImage(String stem, BufferedImage source) {
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return new Image(stem, rgb.getWidth(), rgb.getHeight(), rgb);
    }

    /** Load exported raster images (PNG/JPEG); vector formats cannot be placed into the deck. */
    public static List<Image> load(List<Path> paths) throws IOException {
        List<Image> images = new ArrayList<>();
        for (Path path : paths) {
            BufferedImage img = ImageIO.read(path.toFile());
            if (img == null) {
                throw new ToolException("TOOL_FAILED", "Cannot read exported image " + path
                        + " as a raster image. Use export_format \"png\" (or \"jpeg\") when assembling PPTX/PDF.");
            }
            images.add(fromBufferedImage(stem(path), img));
        }
        return images;
    }

    /** File name without its extension; used to derive slide titles. */
    static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Python {@code str.title()}: upper-case the first cased letter of each run, lower-case the rest. */
    public static String titleCase(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean prevCased = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                sb.append(prevCased ? Character.toLowerCase(c) : Character.toUpperCase(c));
                prevCased = true;
            } else {
                sb.append(c);
                prevCased = false;
            }
        }
        return sb.toString();
    }
}
