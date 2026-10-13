package com.cameomcp.plugin.tools.diagrams;

import com.cameomcp.mcp.ToolException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

/**
 * Downscaling and PNG/JPEG encoding of exported diagram images with javax.imageio / java.awt
 * (the Java port of the reference server's Pillow-based transform). No Cameo dependency.
 */
public final class ImageTransform {

    private ImageTransform() {
    }

    /** Result of {@link #encode}. */
    public static final class Encoded {
        public final byte[] bytes;
        /** "png" or "jpg" (the reference's naming). */
        public final String format;
        public final String mimeType;
        public final int width;
        public final int height;
        /** Non-null when the requested format could not be honored. */
        public final String note;

        Encoded(byte[] bytes, String format, String mimeType, int width, int height, String note) {
            this.bytes = bytes;
            this.format = format;
            this.mimeType = mimeType;
            this.width = width;
            this.height = height;
            this.note = note;
        }
    }

    /** Normalize a requested format to png, jpeg or webp; throws INVALID_PARAMS otherwise. */
    public static String normalizeFormat(String format) {
        String f = format == null || format.trim().isEmpty() ? "png" : format.trim().toLowerCase(Locale.ROOT);
        if (f.equals("jpg")) {
            f = "jpeg";
        }
        if (!f.equals("png") && !f.equals("jpeg") && !f.equals("webp")) {
            throw ToolException.invalid("format must be one of: png, jpeg, jpg, webp (got '" + format + "').");
        }
        return f;
    }

    /**
     * Target size that fits {@code width x height} into the bounds, keeping the aspect ratio and never
     * enlarging (Pillow {@code thumbnail} semantics). Null or non-positive bounds mean unbounded.
     */
    public static int[] fitWithin(int width, int height, Integer maxWidth, Integer maxHeight) {
        int tw = maxWidth != null && maxWidth > 0 ? maxWidth : width;
        int th = maxHeight != null && maxHeight > 0 ? maxHeight : height;
        if (width <= tw && height <= th) {
            return new int[]{width, height};
        }
        double scale = Math.min((double) tw / width, (double) th / height);
        int nw = Math.max(1, (int) Math.round(width * scale));
        int nh = Math.max(1, (int) Math.round(height * scale));
        return new int[]{Math.min(nw, tw), Math.min(nh, th)};
    }

    /** High-quality downscale (progressive halving, then a final bicubic pass). */
    public static BufferedImage resize(BufferedImage src, int targetWidth, int targetHeight) {
        if (src.getWidth() == targetWidth && src.getHeight() == targetHeight) {
            return src;
        }
        boolean alpha = src.getColorModel().hasAlpha();
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage current = src;
        int w = src.getWidth();
        int h = src.getHeight();
        // Halving in bilinear steps first avoids the aliasing a single large bicubic downscale produces.
        while (w / 2 >= targetWidth && h / 2 >= targetHeight && w / 2 > 0 && h / 2 > 0) {
            w /= 2;
            h /= 2;
            current = draw(current, w, h, type, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        if (w != targetWidth || h != targetHeight) {
            current = draw(current, targetWidth, targetHeight, type, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        }
        return current;
    }

    // Redraws src at w x h into a new image of the given type with quality rendering hints.
    private static BufferedImage draw(BufferedImage src, int w, int h, int type, Object interpolation) {
        BufferedImage out = new BufferedImage(w, h, type);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Copy onto an opaque white RGB canvas (JPEG has no alpha channel). */
    public static BufferedImage flattenOnWhite(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB || src.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, src.getWidth(), src.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    // Lossless PNG bytes of the image.
    public static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("No PNG writer available");
        }
        return out.toByteArray();
    }

    // JPEG bytes at quality 1-100 (mapped to ImageIO's 0.01-1.0 compression quality); alpha is flattened first.
    public static byte[] jpeg(BufferedImage image, int quality) throws IOException {
        BufferedImage rgb = flattenOnWhite(image);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(1, Math.min(quality, 100)) / 100f);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /**
     * Resize (when bounds are given) and encode. {@code format} is a value from {@link #normalizeFormat};
     * webp is not available in ImageIO, so it is encoded as PNG and a note says so.
     */
    public static Encoded encode(BufferedImage image, String format, Integer maxWidth, Integer maxHeight, int quality)
            throws IOException {
        BufferedImage out = image;
        if (maxWidth != null || maxHeight != null) {
            int[] size = fitWithin(image.getWidth(), image.getHeight(), maxWidth, maxHeight);
            out = resize(image, size[0], size[1]);
        }
        if ("jpeg".equals(format)) {
            return new Encoded(jpeg(out, quality), "jpg", "image/jpeg", out.getWidth(), out.getHeight(), null);
        }
        String note = "webp".equals(format)
                ? "WebP encoding is not available in Cameo's Java runtime; returned PNG instead. Use format=jpeg for a smaller payload."
                : null;
        return new Encoded(png(out), "png", "image/png", out.getWidth(), out.getHeight(), note);
    }
}
