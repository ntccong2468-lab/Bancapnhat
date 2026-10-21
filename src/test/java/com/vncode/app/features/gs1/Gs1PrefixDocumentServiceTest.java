package com.vncode.app.features.gs1;

import com.vncode.app.features.gs1.Gs1PrefixDocumentService.*;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseRuntime;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class Gs1PrefixDocumentServiceTest {
    @TempDir Path directory;
    private final Gs1PrefixDocumentService service = new Gs1PrefixDocumentService();
    private final Applicant applicant = new Applicant("Тестовая организация", "7707083893", "RU-12345", LocalDate.of(2026, 10, 12), "Иванов Иван Иванович");

    @Test void fillsReviewedOfficialBlankKeepsVisibleBackgroundAndDropsPdfActions() throws Exception {
        byte[] source = blank(1);
        byte[] before = source.clone();
        try (var ink = signature()) {
            var attachment = service.signedTemplate(source, applicant, layout(), ink);
            assertEquals("application/pdf", attachment.type());
            assertEquals("GS1-01.04.01.pdf", attachment.name());
            assertArrayEquals(before, source);
            try (PDDocument output = Loader.loadPDF(attachment.bytes())) {
                assertEquals(1, output.getNumberOfPages());
                assertNull(output.getDocumentCatalog().getOpenAction());
                assertTrue(output.getPage(0).getAnnotations().isEmpty());
                String text = new PDFTextStripper().getText(output);
                assertTrue(text.contains(applicant.legalName())); assertTrue(text.contains(applicant.inn()));
                assertTrue(text.contains(applicant.gs1Code())); assertTrue(text.contains("12.10.2026")); assertTrue(text.contains(applicant.signerName()));
                var names = output.getPage(0).getResources().getXObjectNames().iterator();
                assertTrue(names.hasNext());
                var image = (PDImageXObject) output.getPage(0).getResources().getXObject(names.next());
                assertTrue(image.getWidth() > 1000, "The actual supplied blank is retained as the visible background");
                String operators = new String(output.getPage(0).getContents().readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertTrue(operators.contains(" l\n"), "The completed attachment includes signature vector strokes");
            }
        }
    }

    @Test void requiresAllFieldsSignatureAndReadablePlacement() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new Layout(new EnumMap<>(Field.class), new Area(.1, .1, .2, .1)));
        try (var empty = new Gs1SignatureInk()) {
            assertThrows(IllegalArgumentException.class, () -> service.signedTemplate(blank(1), applicant, layout(), empty));
            empty.start(.5, .5); empty.append(.5, .5); empty.end();
            assertTrue(empty.isEmpty(), "A single dot is insufficient as a signature");
        }
        var positions = new EnumMap<>(layout().fields()); positions.put(Field.COMPANY_NAME, new Area(.1, .1, .01, .01));
        try (var ink = signature()) {
            assertThrows(IllegalArgumentException.class, () -> service.signedTemplate(blank(1), applicant, new Layout(positions, layout().signature()), ink));
        }
        assertThrows(IllegalArgumentException.class, () -> new Area(Double.NaN, .1, .2, .1));
        assertThrows(IllegalArgumentException.class, () -> new Area(.9, .1, .2, .1));
    }

    @Test void paperPhotoExifOrientationSixIsActuallyAppliedToEmbeddedImage() throws Exception {
        var image = new BufferedImage(60, 30, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, x < 30 ? Color.RED.getRGB() : Color.BLUE.getRGB());
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(); ImageIO.write(image, "jpeg", jpeg);
        var attachment = service.paperPhoto(withExif(jpeg.toByteArray(), 6, ByteOrder.LITTLE_ENDIAN));
        try (var pdf = Loader.loadPDF(attachment.bytes())) {
            assertEquals(1, pdf.getNumberOfPages()); assertEquals(PDRectangle.A4.getWidth(), pdf.getPage(0).getCropBox().getWidth());
            var imageName = pdf.getPage(0).getResources().getXObjectNames().iterator().next();
            var embedded = ((PDImageXObject) pdf.getPage(0).getResources().getXObject(imageName)).getImage();
            assertEquals(30, embedded.getWidth()); assertEquals(60, embedded.getHeight());
            Color upper = new Color(embedded.getRGB(15, 10)), lower = new Color(embedded.getRGB(15, 50));
            assertTrue(upper.getRed() > 200 && upper.getBlue() < 50);
            assertTrue(lower.getBlue() > 200 && lower.getRed() < 50);
        }
    }

    @Test void allEightExifOrientationsHandleMirroringAndBothByteOrders() {
        var source = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, Color.RED.getRGB()); source.setRGB(1, 2, Color.BLUE.getRGB());
        int[][] redPositions = {{0,0},{1,0},{1,2},{0,2},{0,0},{2,0},{2,1},{0,1}};
        for (int orientation = 1; orientation <= 8; orientation++) {
            for (ByteOrder order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN})
                assertEquals(orientation, Gs1PrefixDocumentService.exifOrientation(withExif(new byte[]{(byte)255,(byte)216,(byte)255,(byte)217}, orientation, order)));
            var transformed = Gs1PrefixDocumentService.orient(source, orientation);
            assertEquals(orientation >= 5 ? 3 : 2, transformed.getWidth());
            assertEquals(orientation >= 5 ? 2 : 3, transformed.getHeight());
            assertEquals(Color.RED.getRGB() & 0xffffff, transformed.getRGB(redPositions[orientation - 1][0], redPositions[orientation - 1][1]) & 0xffffff);
        }
    }

    @Test void pngExifOrientationIsAppliedAndTransparencyBecomesWhitePaper() throws Exception {
        var image = new BufferedImage(10, 20, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 2, Color.RED.getRGB());
        ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(image, "png", png);
        byte[] exifJpeg = withExif(new byte[]{(byte)255,(byte)216,(byte)255,(byte)217}, 8, ByteOrder.BIG_ENDIAN);
        byte[] tiff = java.util.Arrays.copyOfRange(exifJpeg, 12, 38);
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        chunk.write(ByteBuffer.allocate(4).putInt(tiff.length).array()); chunk.write(new byte[]{'e','X','I','f'}); chunk.write(tiff);
        CRC32 crc = new CRC32(); crc.update(new byte[]{'e','X','I','f'}); crc.update(tiff); chunk.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        byte[] original = png.toByteArray(); ByteArrayOutputStream oriented = new ByteArrayOutputStream();
        oriented.write(original, 0, 33); oriented.write(chunk.toByteArray()); oriented.write(original, 33, original.length - 33);
        assertEquals(8, Gs1PrefixDocumentService.exifOrientation(oriented.toByteArray()));
        var attachment = service.paperPhoto(oriented.toByteArray());
        try (var pdf = Loader.loadPDF(attachment.bytes())) {
            var name = pdf.getPage(0).getResources().getXObjectNames().iterator().next();
            var embedded = ((PDImageXObject) pdf.getPage(0).getResources().getXObject(name)).getImage();
            assertEquals(20, embedded.getWidth()); assertEquals(10, embedded.getHeight());
            Color paper = new Color(embedded.getRGB(18, 8));
            assertTrue(paper.getRed() > 230 && paper.getGreen() > 230 && paper.getBlue() > 230);
        }
    }

    @Test void invalidOrOversizedDocumentsFailBeforeImagePixelDecode() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.paperPhoto(new byte[]{1,2,3}));
        assertThrows(java.io.IOException.class, () -> service.paperPhoto(new byte[]{(byte)255,(byte)216,(byte)255,(byte)217}));
        assertThrows(IllegalArgumentException.class, () -> service.previewTemplate(blank(2)));
        assertThrows(IllegalArgumentException.class, () -> service.previewTemplate(new byte[10 * 1024 * 1024 + 1]));
        ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", png);
        byte[] oversizedDimensions = png.toByteArray(); ByteBuffer.wrap(oversizedDimensions).putInt(16, 10001);
        CRC32 crc = new CRC32(); crc.update(oversizedDimensions, 12, 17); ByteBuffer.wrap(oversizedDimensions).putInt(29, (int) crc.getValue());
        assertThrows(IllegalArgumentException.class, () -> service.paperPhoto(oversizedDimensions));
        byte[] malformedExif = withExif(new byte[]{(byte)255,(byte)216,(byte)255,(byte)217}, 6, ByteOrder.LITTLE_ENDIAN);
        malformedExif[16] = (byte)255; malformedExif[17] = (byte)255; malformedExif[18] = (byte)255; malformedExif[19] = (byte)127;
        assertEquals(1, Gs1PrefixDocumentService.exifOrientation(malformedExif));
    }

    @Test void deniedLicenseStopsBeforeUntrustedDocumentParsing() throws Exception {
        var previous = LicenseRuntime.service();
        try (var denied = LicenseDomainFixture.denied(directory.resolve("license")); var ink = signature()) {
            LicenseRuntime.install(denied);
            assertThrows(LicenseDeniedException.class, () -> service.paperPhoto(null));
            assertThrows(LicenseDeniedException.class, () -> service.signedTemplate(null, applicant, layout(), ink));
        } finally { LicenseRuntime.install(previous); }
    }

    @Test void oversizedPdfImageIsRejectedBeforeAnyPixelDecodeEvenInsideNestedForm() throws Exception {
        for (int[] dimensions : new int[][]{{10001, 1}, {5001, 5000}}) {
            byte[] tinyPdf = resourcePdf(dimensions[0], dimensions[1], 2, false);
            assertTrue(tinyPdf.length < 4000, "Synthetic image stream contains one byte, no allocated large bitmap");
            var failure = assertThrows(IllegalArgumentException.class, () -> service.previewTemplate(tinyPdf));
            assertEquals("gs1.document_resource_limits", failure.getMessage());
        }
    }

    @Test void excessivelyNestedAndCyclicPdfFormResourcesAreRejectedBeforeRendering() throws Exception {
        assertEquals("gs1.document_resource_limits", assertThrows(IllegalArgumentException.class,
                () -> service.previewTemplate(resourcePdf(1, 1, 9, false))).getMessage());
        assertEquals("gs1.document_resource_limits", assertThrows(IllegalArgumentException.class,
                () -> service.previewTemplate(resourcePdf(1, 1, 1, true))).getMessage());
    }

    @Test void rawInkCannotBeReusedAfterDialogBufferIsClosed() {
        var ink = signature(); assertFalse(ink.isEmpty()); ink.close(); assertTrue(ink.isEmpty());
        assertThrows(IllegalStateException.class, ink::snapshot); assertThrows(IllegalStateException.class, () -> ink.start(.1, .2));
        assertFalse(applicant.toString().contains(applicant.inn())); assertFalse(applicant.toString().contains(applicant.legalName()));
    }

    private static Gs1SignatureInk signature() {
        var ink = new Gs1SignatureInk(); ink.start(.1, .8); ink.append(.4, .1); ink.append(.7, .7); ink.end(); return ink;
    }
    private static Layout layout() {
        var placements = new EnumMap<Field, Area>(Field.class); int i = 0;
        for (Field field : Field.values()) placements.put(field, new Area(.1, .82 - .1 * i++, .8, .04));
        return new Layout(placements, new Area(.15, .13, .25, .1));
    }
    private static byte[] blank(int pages) throws Exception {
        try (var document = new PDDocument()) {
            document.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('not retained')"));
            for (int i = 0; i < pages; i++) {
                var page = new PDPage(PDRectangle.A4); document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.setStrokingColor(Color.BLACK); content.addRect(30, 30, 530, 780); content.stroke();
                }
            }
            var output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray();
        }
    }
    private static byte[] resourcePdf(int width, int height, int formDepth, boolean cycle) throws Exception {
        try (var document = new PDDocument()) {
            var page = new PDPage(PDRectangle.A4); document.addPage(page); page.setResources(new PDResources());
            var image = new PDImageXObject(document, new ByteArrayInputStream(new byte[]{0}), null, width, height, 8, PDDeviceRGB.INSTANCE);
            PDResources resources = page.getResources();
            for (int i = 0; i < formDepth; i++) {
                var form = new PDFormXObject(document); form.setBBox(PDRectangle.A4); form.setResources(new PDResources());
                resources.put(COSName.getPDFName("Nested"), form);
                if (cycle && i == 0) form.getResources().put(COSName.getPDFName("Self"), form);
                resources = form.getResources();
            }
            resources.put(COSName.getPDFName("Oversized"), image);
            var output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray();
        }
    }
    private static byte[] withExif(byte[] jpeg, int orientation, ByteOrder order) {
        byte[] block = new byte[36]; block[0] = (byte)255; block[1] = (byte)225; block[2] = 0; block[3] = 34;
        block[4]='E'; block[5]='x'; block[6]='i'; block[7]='f';
        ByteBuffer buffer = ByteBuffer.wrap(block).order(order); block[10] = block[11] = order == ByteOrder.LITTLE_ENDIAN ? (byte)'I' : (byte)'M';
        buffer.putShort(12, (short)42); buffer.putInt(14, 8); buffer.putShort(18, (short)1);
        buffer.putShort(20, (short)274); buffer.putShort(22, (short)3); buffer.putInt(24, 1); buffer.putShort(28, (short)orientation);
        var result = new byte[jpeg.length + block.length]; System.arraycopy(jpeg, 0, result, 0, 2);
        System.arraycopy(block, 0, result, 2, block.length); System.arraycopy(jpeg, 2, result, 2 + block.length, jpeg.length - 2); return result;
    }
}
