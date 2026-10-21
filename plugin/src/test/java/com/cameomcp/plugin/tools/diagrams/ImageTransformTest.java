// family: diagrams
package com.cameomcp.plugin.tools.diagrams;

import com.cameomcp.mcp.ToolException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ImageTransform}: aspect-preserving fit, format normalization, JPEG on white, PNG round
 * trips, the WebP fallback and quality versus size.
 */
class ImageTransformTest {

    private static BufferedImage sample(int w, int h, boolean alpha) {
        BufferedImage img = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        return img;
    }

    @Test
    void fitWithinKeepsAspectAndNeverEnlarges() {
        assertArrayEquals(new int[]{500, 250}, ImageTransform.fitWithin(2000, 1000, 500, null));
        assertArrayEquals(new int[]{200, 100}, ImageTransform.fitWithin(2000, 1000, 500, 100));
        assertArrayEquals(new int[]{300, 200}, ImageTransform.fitWithin(300, 200, 1000, 1000));
        assertArrayEquals(new int[]{300, 200}, ImageTransform.fitWithin(300, 200, null, null));
        assertArrayEquals(new int[]{1, 1}, ImageTransform.fitWithin(5000, 10, 1, 1));
    }

    @Test
    void normalizeFormat() {
        assertEquals("jpeg", ImageTransform.normalizeFormat("JPG"));
        assertEquals("png", ImageTransform.normalizeFormat(null));
        assertEquals("webp", ImageTransform.normalizeFormat("webp"));
        assertThrows(ToolException.class, () -> ImageTransform.normalizeFormat("gif"));
    }

    @Test
    void resizesAndEncodesJpegOnWhite() throws Exception {
        BufferedImage src = sample(1600, 800, true); // transparent background
        ImageTransform.Encoded e = ImageTransform.encode(src, "jpeg", 400, null, 80);
        assertEquals("jpg", e.format);
        assertEquals("image/jpeg", e.mimeType);
        assertEquals(400, e.width);
        assertEquals(200, e.height);
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(e.bytes));
        assertNotNull(back);
        assertEquals(400, back.getWidth());
        Color corner = new Color(back.getRGB(2, 2));
        assertTrue(corner.getRed() > 240 && corner.getGreen() > 240 && corner.getBlue() > 240,
                "transparent pixels must be flattened onto white, got " + corner);
        Color center = new Color(back.getRGB(200, 100));
        assertTrue(center.getBlue() > 180 && center.getRed() < 80);
    }

    @Test
    void pngRoundTripAndWebpFallback() throws Exception {
        BufferedImage src = sample(300, 120, false);
        ImageTransform.Encoded png = ImageTransform.encode(src, "png", null, null, 85);
        assertNull(png.note);
        assertEquals(300, ImageIO.read(new ByteArrayInputStream(png.bytes)).getWidth());
        ImageTransform.Encoded webp = ImageTransform.encode(src, "webp", 150, 150, 85);
        assertEquals("png", webp.format);
        assertNotNull(webp.note);
        assertEquals(150, webp.width);
        assertEquals(60, webp.height);
    }

    @Test
    void lowerQualityGivesSmallerJpeg() throws Exception {
        BufferedImage src = sample(800, 800, false);
        int q90 = ImageTransform.jpeg(src, 90).length;
        int q10 = ImageTransform.jpeg(src, 10).length;
        assertTrue(q10 < q90);
    }
}
