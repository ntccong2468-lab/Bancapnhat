package com.vncode.app.features.gs1;

import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.gs1.Gs1MailClient;
import com.vncode.app.integration.gs1.Gs1Membership;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Builds attachments from a reviewed official blank supplied by the operator, or a paper-form photo.
 * No replacement GS1 form is invented. The blank and all field locations remain visible in the dialog.
 */
public final class Gs1PrefixDocumentService {
    public enum Field { COMPANY_NAME, INN, GS1_CODE, DATE, SIGNER_NAME }
    public record Applicant(String legalName, String inn, String gs1Code, LocalDate date, String signerName) {
        public Applicant {
            legalName = text(legalName, 500); Gs1Membership.requireInn(inn);
            gs1Code = text(gs1Code, 80); signerName = text(signerName, 200); Objects.requireNonNull(date);
        }
        public String value(Field field) {
            return switch (field) {
                case COMPANY_NAME -> legalName; case INN -> inn; case GS1_CODE -> gs1Code;
                case DATE -> date.format(DateTimeFormatter.ofPattern("dd.MM.uuuu")); case SIGNER_NAME -> signerName;
            };
        }
        @Override public String toString() { return "Gs1PrefixApplicant[redacted]"; }
    }
    /** Bottom-left origin, normalized against the displayed page (after page rotation). */
    public record Area(double x, double y, double width, double height) {
        public Area {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height) ||
                    x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > 1.000001 || y + height > 1.000001)
                throw new IllegalArgumentException("Invalid form placement");
        }
    }
    public record Layout(Map<Field, Area> fields, Area signature) {
        public Layout {
            fields = Map.copyOf(fields); Objects.requireNonNull(signature);
            if (fields.size() != Field.values().length || !fields.keySet().containsAll(List.of(Field.values())))
                throw new IllegalArgumentException("Place every form field before creating the attachment");
        }
    }
    public record Preview(byte[] png, float width, float height) {
        public Preview { png = png.clone(); }
        @Override public byte[] png() { return png.clone(); }
        @Override public String toString() { return "Gs1FormPreview[redacted]"; }
    }
    private static final long MAX_IMAGE_PIXELS = 25_000_000;
    private static final int MAX_IMAGE_SIDE = 10_000;
    private static final int PHOTO_OUTPUT_SIDE = 2600;

    public Preview previewTemplate(byte[] suppliedBlank) throws IOException {
        var page = template(suppliedBlank, 110);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(page.image(), "png", png);
        return new Preview(png.toByteArray(), page.width(), page.height());
    }

    public Gs1MailClient.Attachment signedTemplate(byte[] suppliedBlank, Applicant applicant,
                                                  Layout reviewedLayout, Gs1SignatureInk ink) throws IOException {
        LicenseRuntime.require(Permission.GS1);
        Objects.requireNonNull(applicant); Objects.requireNonNull(reviewedLayout); Objects.requireNonNull(ink);
        if (ink.isEmpty()) throw new IllegalArgumentException("Draw a signature before creating the attachment");
        var background = template(suppliedBlank, 180);
        try (PDDocument output = new PDDocument()) {
            var page = new PDPage(new PDRectangle(background.width(), background.height())); output.addPage(page);
            PDType0Font font;
            try (var stream = Gs1PrefixDocumentService.class.getResourceAsStream("/com/vncode/app/assets/fonts/ARIAL.TTF")) {
                if (stream == null) throw new IOException("gs1.document_font_unavailable");
                font = PDType0Font.load(output, stream);
            }
            try (var content = new PDPageContentStream(output, page)) {
                // Rasterizing the supplied blank drops scripts, actions, forms and external links from the attachment.
                content.drawImage(LosslessFactory.createFromImage(output, background.image()), 0, 0, background.width(), background.height());
                content.setNonStrokingColor(Color.BLACK);
                for (Field field : Field.values()) {
                    var area = reviewedLayout.fields().get(field);
                    String value = applicant.value(field);
                    float size = (float) Math.min(11, area.height() * background.height() * 0.85);
                    float availableWidth = (float) (area.width() * background.width());
                    float textWidth = font.getStringWidth(value) / 1000 * size;
                    if (textWidth > availableWidth) size *= availableWidth / textWidth;
                    if (size < 7) throw new IllegalArgumentException("gs1.document_field_too_small");
                    content.beginText(); content.setFont(font, size);
                    content.newLineAtOffset((float) (area.x() * background.width()), (float) (area.y() * background.height() + size * 0.2));
                    content.showText(value); content.endText();
                }
                var area = reviewedLayout.signature();
                content.setStrokingColor(Color.BLACK); content.setLineWidth(1.2f);
                List<double[][]> copy = ink.snapshot();
                try {
                    for (double[][] stroke : copy) {
                        if (stroke.length < 2) continue;
                        content.moveTo(signatureX(stroke[0][0], area, background.width()), signatureY(stroke[0][1], area, background.height()));
                        for (int i = 1; i < stroke.length; i++) content.lineTo(signatureX(stroke[i][0], area, background.width()), signatureY(stroke[i][1], area, background.height()));
                        content.stroke();
                    }
                } finally { for (var stroke : copy) for (var point : stroke) java.util.Arrays.fill(point, 0); }
            }
            return attachment(output);
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (IOException failure) { throw new IOException("gs1.document_failed"); }
    }

    public Gs1MailClient.Attachment paperPhoto(byte[] photo) throws IOException {
        LicenseRuntime.require(Permission.GS1);
        requireSize(photo);
        if (!isJpeg(photo) && !isPng(photo)) throw new IllegalArgumentException("gs1.document_photo_only");
        BufferedImage source;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(photo))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("gs1.document_invalid_photo");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE || (long) width * height > MAX_IMAGE_PIXELS)
                    throw new IllegalArgumentException("gs1.document_photo_dimensions");
                source = reader.read(0);
            } finally { reader.dispose(); }
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (IOException failure) { throw new IOException("gs1.document_invalid_photo"); }
        BufferedImage upright = orient(source, exifOrientation(photo));
        BufferedImage reduced = resize(upright, PHOTO_OUTPUT_SIDE);
        try (PDDocument output = new PDDocument()) {
            PDRectangle pageSize = reduced.getWidth() > reduced.getHeight() ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()) : PDRectangle.A4;
            var page = new PDPage(pageSize); output.addPage(page);
            float scale = Math.min((pageSize.getWidth() - 24) / reduced.getWidth(), (pageSize.getHeight() - 24) / reduced.getHeight());
            float width = reduced.getWidth() * scale, height = reduced.getHeight() * scale;
            try (var content = new PDPageContentStream(output, page)) {
                content.drawImage(JPEGFactory.createFromImage(output, reduced, .90f), (pageSize.getWidth() - width) / 2, (pageSize.getHeight() - height) / 2, width, height);
            }
            return attachment(output);
        } catch (IOException failure) { throw new IOException("gs1.document_failed"); }
    }

    private record TemplatePage(BufferedImage image, float width, float height) { }
    private static TemplatePage template(byte[] bytes, float dpi) throws IOException {
        requireSize(bytes);
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-')
            throw new IllegalArgumentException("gs1.document_pdf_only");
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted() || document.getNumberOfPages() != 1) throw new IllegalArgumentException("gs1.document_single_page");
            var page = document.getPage(0);
            if (Math.floorMod(page.getRotation(), 90) != 0) throw new IllegalArgumentException("gs1.document_page_size");
            float width = page.getCropBox().getWidth(), height = page.getCropBox().getHeight();
            if (!Float.isFinite(width) || !Float.isFinite(height) || width < 200 || height < 200 || width > 1000 || height > 1000)
                throw new IllegalArgumentException("gs1.document_page_size");
            if (Math.floorMod(page.getRotation(), 180) != 0) { float temp = width; width = height; height = temp; }
            // Inspect raw resource dictionaries before constructing image objects or decoding streams.
            // A tiny PDF can otherwise describe a huge image stretched onto a small A4 page.
            var limits = new ResourceLimits();
            if (page.getResources() != null) limits.inspect(page.getResources().getCOSObject(), 0, 0);
            for (var annotation : page.getAnnotations()) {
                var appearance = annotation.getAppearance();
                if (appearance != null) limits.inspect(appearance.getCOSObject(), 0, 0);
            }
            var renderer = new PDFRenderer(document); renderer.setSubsamplingAllowed(true);
            var image = renderer.renderImageWithDPI(0, dpi, ImageType.RGB);
            return new TemplatePage(image, width, height);
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (IOException failure) { throw new IOException("gs1.document_invalid_pdf"); }
    }
    private static final class ResourceLimits {
        private static final int MAX_OBJECTS = 2048, MAX_DEPTH = 48, MAX_FORMS = 8;
        private final Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<COSBase> active = Collections.newSetFromMap(new IdentityHashMap<>());

        void inspect(COSBase value, int depth, int forms) {
            if (value == null) return;
            if (depth > MAX_DEPTH || forms > MAX_FORMS || active.contains(value)) reject();
            if (!visited.add(value)) return;
            if (visited.size() > MAX_OBJECTS) reject();
            active.add(value);
            try {
                if (value instanceof COSObject reference) { inspect(reference.getObject(), depth + 1, forms); return; }
                if (value instanceof COSArray array) {
                    for (int i = 0; i < array.size(); i++) inspect(array.get(i), depth + 1, forms);
                } else if (value instanceof COSDictionary dictionary) {
                    var subtype = dictionary.getCOSName(COSName.SUBTYPE);
                    boolean form = COSName.FORM.equals(subtype);
                    if (COSName.IMAGE.equals(subtype)) {
                        int width = dictionary.getInt(COSName.WIDTH), height = dictionary.getInt(COSName.HEIGHT);
                        if (width < 1 || height < 1 || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE || (long) width * height > MAX_IMAGE_PIXELS)
                            reject();
                    }
                    if (form && forms >= MAX_FORMS) reject();
                    for (var entry : dictionary.entrySet()) inspect(entry.getValue(), depth + 1, forms + (form ? 1 : 0));
                }
            } finally { active.remove(value); }
        }
        private static void reject() { throw new IllegalArgumentException("gs1.document_resource_limits"); }
    }
    private static Gs1MailClient.Attachment attachment(PDDocument output) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); output.save(bytes);
        return new Gs1MailClient.Attachment("GS1-01.04.01.pdf", "application/pdf", bytes.toByteArray());
    }
    private static float signatureX(double x, Area area, float width) { return (float) ((area.x() + x * area.width()) * width); }
    private static float signatureY(double y, Area area, float height) { return (float) ((area.y() + (1 - y) * area.height()) * height); }
    private static String text(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("gs1.document_invalid_fields");
        return value.strip();
    }
    private static void requireSize(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > Gs1MailClient.MAX_ATTACHMENT)
            throw new IllegalArgumentException("gs1.document_size");
    }
    private static boolean isJpeg(byte[] bytes) { return bytes.length > 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255; }
    private static boolean isPng(byte[] bytes) { return bytes.length > 8 && java.util.Arrays.equals(java.util.Arrays.copyOf(bytes, 8), new byte[]{(byte)137,80,78,71,13,10,26,10}); }

    /** Reads only TIFF IFD0 orientation in JPEG APP1 or PNG eXIf; ignores unrelated metadata. */
    static int exifOrientation(byte[] bytes) {
        if (isPng(bytes)) {
            int offset = 8;
            while (offset + 12 <= bytes.length) {
                long length = Integer.toUnsignedLong(ByteBuffer.wrap(bytes).getInt(offset));
                if (length > bytes.length - offset - 12L) return 1;
                if (bytes[offset + 4] == 'e' && bytes[offset + 5] == 'X' && bytes[offset + 6] == 'I' && bytes[offset + 7] == 'f')
                    return tiffOrientation(bytes, offset + 8, offset + 8 + (int) length);
                offset += (int) length + 12;
            }
            return 1;
        }
        if (!isJpeg(bytes)) return 1;
        int offset = 2;
        while (offset + 4 <= bytes.length) {
            if ((bytes[offset++] & 255) != 255) return 1;
            int marker = bytes[offset++] & 255;
            if (marker == 218 || marker == 217) return 1;
            if (marker == 0 || marker == 255 || marker == 1 || (marker >= 208 && marker <= 215)) continue;
            int length = ((bytes[offset] & 255) << 8) | (bytes[offset + 1] & 255);
            if (length < 2 || offset + length > bytes.length) return 1;
            if (marker == 225 && length >= 16 && bytes[offset + 2] == 'E' && bytes[offset + 3] == 'x' && bytes[offset + 4] == 'i' && bytes[offset + 5] == 'f' && bytes[offset + 6] == 0 && bytes[offset + 7] == 0) {
                return tiffOrientation(bytes, offset + 8, offset + length);
            }
            offset += length;
        }
        return 1;
    }
    private static int tiffOrientation(byte[] bytes, int base, int end) {
        if (base < 0 || end > bytes.length || end - base < 8) return 1;
        ByteOrder order;
        if (bytes[base] == 'I' && bytes[base + 1] == 'I') order = ByteOrder.LITTLE_ENDIAN;
        else if (bytes[base] == 'M' && bytes[base + 1] == 'M') order = ByteOrder.BIG_ENDIAN;
        else return 1;
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(order);
        if (Short.toUnsignedInt(buffer.getShort(base + 2)) != 42) return 1;
        long relative = Integer.toUnsignedLong(buffer.getInt(base + 4));
        if (relative < 8 || relative > end - base - 2L) return 1;
        int directory = base + (int) relative, count = Short.toUnsignedInt(buffer.getShort(directory));
        for (int i = 0; i < count; i++) {
            long position = directory + 2L + i * 12L;
            if (position + 12 > end) return 1;
            int p = (int) position;
            if (Short.toUnsignedInt(buffer.getShort(p)) == 274 && Short.toUnsignedInt(buffer.getShort(p + 2)) == 3 && buffer.getInt(p + 4) == 1) {
                int value = Short.toUnsignedInt(buffer.getShort(p + 8)); return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }
    static BufferedImage orient(BufferedImage source, int orientation) {
        int width = source.getWidth(), height = source.getHeight();
        boolean swapped = orientation >= 5 && orientation <= 8;
        var result = new BufferedImage(swapped ? height : width, swapped ? width : height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int targetX = x, targetY = y;
            switch (orientation) {
                case 2 -> targetX = width - 1 - x;
                case 3 -> { targetX = width - 1 - x; targetY = height - 1 - y; }
                case 4 -> targetY = height - 1 - y;
                case 5 -> { targetX = y; targetY = x; }
                case 6 -> { targetX = height - 1 - y; targetY = x; }
                case 7 -> { targetX = height - 1 - y; targetY = width - 1 - x; }
                case 8 -> { targetX = y; targetY = width - 1 - x; }
                default -> { }
            }
            int argb = source.getRGB(x, y), alpha = argb >>> 24;
            int red = ((argb >>> 16 & 255) * alpha + 255 * (255 - alpha)) / 255;
            int green = ((argb >>> 8 & 255) * alpha + 255 * (255 - alpha)) / 255;
            int blue = ((argb & 255) * alpha + 255 * (255 - alpha)) / 255;
            result.setRGB(targetX, targetY, (red << 16) | (green << 8) | blue);
        }
        return result;
    }
    private static BufferedImage resize(BufferedImage source, int maximum) {
        double scale = Math.min(1, (double) maximum / Math.max(source.getWidth(), source.getHeight()));
        if (scale == 1) return source;
        var result = new BufferedImage(Math.max(1, (int) (source.getWidth() * scale)), Math.max(1, (int) (source.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try { graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); graphics.drawImage(source, 0, 0, result.getWidth(), result.getHeight(), null); }
        finally { graphics.dispose(); }
        return result;
    }
}
