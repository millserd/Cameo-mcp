package com.cameomcp.plugin.tools.analysis;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Shared test fixtures for the analysis family. */
public final class Fixtures {

    private Fixtures() {
    }

    /** White RGBA PNG with a black square from (10,10) to (40,40), as a get_diagram_image payload. */
    public static Map<String, Object> pngPayload(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.fillRect(10, 10, 31, 31);
        g.dispose();
        return J.obj("image", base64Png(image), "width", width, "height", height);
    }

    public static String solidPngBase64(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return base64Png(image);
    }

    public static String base64Png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SafeVarargs
    public static List<Map<String, Object>> maps(Map<String, Object>... items) {
        return new ArrayList<>(List.of(items));
    }

    public static List<String> strs(String... items) {
        return new ArrayList<>(List.of(items));
    }

    /** First check with the given name. */
    public static Map<String, Object> check(Map<String, Object> result, String name) {
        for (Map<String, Object> c : J.maps(result.get("checks"))) {
            if (name.equals(c.get("name"))) {
                return c;
            }
        }
        throw new AssertionError("no check named " + name + " in " + result.get("checks"));
    }

    public static Map<String, Object> rel(String id, String type, String source, String target) {
        return J.obj("relationshipId", id, "type", type,
                "sources", J.arr(J.obj("id", source)), "targets", J.arr(J.obj("id", target)));
    }
}
