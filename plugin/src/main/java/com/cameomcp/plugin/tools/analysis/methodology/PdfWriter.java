package com.cameomcp.plugin.tools.analysis.methodology;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal PDF 1.4 writer: one page per image, page size = image size in points (72 dpi),
 * each image embedded as a JPEG (DCTDecode) XObject. Output has a correct xref table.
 */
public final class PdfWriter {

    private PdfWriter() {
    }

    /** Writes the PDF to {@code output}, replacing any existing file. */
    public static void write(List<OfficeImages.Image> images, Path output) throws IOException {
        Files.write(output, toBytes(images));
    }

    /** Builds the PDF in memory; throws IllegalArgumentException for an empty image list. */
    public static byte[] toBytes(List<OfficeImages.Image> images) throws IOException {
        if (images.isEmpty()) {
            throw new IllegalArgumentException("cannot assemble PDF without exported images");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        int pageCount = images.size();
        // Object layout: 1 = catalog, 2 = page tree, then three objects per page (page, image, content stream).
        int objectCount = 2 + pageCount * 3;
        ascii(out, "%PDF-1.4\n");
        // Binary comment line: tells transfer tools the file is binary, as the PDF spec recommends.
        out.write(new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});

        // offsets records each object's byte position in object-number order, for the xref table.
        offsets.add(out.size());
        ascii(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pageCount; i++) {
            kids.append(3 + i * 3).append(" 0 R ");
        }
        offsets.add(out.size());
        ascii(out, "2 0 obj\n<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pageCount + " >>\nendobj\n");

        for (int i = 0; i < pageCount; i++) {
            OfficeImages.Image image = images.get(i);
            int pageObj = 3 + i * 3;
            int imageObj = pageObj + 1;
            int contentObj = pageObj + 2;
            int w = image.width();
            int h = image.height();

            offsets.add(out.size());
            ascii(out, pageObj + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + w + " " + h + "] "
                    + "/Resources << /XObject << /Im0 " + imageObj + " 0 R >> /ProcSet [/PDF /ImageC] >> "
                    + "/Contents " + contentObj + " 0 R >>\nendobj\n");

            // Raw JPEG bytes are a valid DCTDecode stream, so no re-encoding is needed.
            byte[] jpeg = image.jpeg(0.92f);
            offsets.add(out.size());
            ascii(out, imageObj + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + w + " /Height " + h
                    + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + jpeg.length
                    + " >>\nstream\n");
            out.write(jpeg);
            ascii(out, "\nendstream\nendobj\n");

            // Content stream: scale the unit image square to the full page and draw it.
            byte[] content = String.format(Locale.ROOT, "q\n%d 0 0 %d 0 0 cm\n/Im0 Do\nQ\n", w, h)
                    .getBytes(StandardCharsets.US_ASCII);
            offsets.add(out.size());
            ascii(out, contentObj + " 0 obj\n<< /Length " + content.length + " >>\nstream\n");
            out.write(content);
            ascii(out, "endstream\nendobj\n");
        }

        int xref = out.size();
        StringBuilder sb = new StringBuilder();
        sb.append("xref\n0 ").append(objectCount + 1).append('\n');
        // Entry for the free object 0 required at the head of every xref table.
        sb.append("0000000000 65535 f \n");
        for (int offset : offsets) {
            sb.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset));
        }
        sb.append("trailer\n<< /Size ").append(objectCount + 1).append(" /Root 1 0 R >>\n");
        sb.append("startxref\n").append(xref).append("\n%%EOF\n");
        ascii(out, sb.toString());
        return out.toByteArray();
    }

    private static void ascii(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
    }
}
