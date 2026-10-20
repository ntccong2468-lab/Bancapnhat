package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.KizService;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonCombinedLabelRendererTest {
    private static final String BARCODE = "OZN0000000000";
    private static final String KIZ = "010000000000000021DEMO000000001\u001d91TEST\u001d92NOTVALID";
    @TempDir Path temporaryDirectory;

    @BeforeEach void setUp() {
        System.setProperty("vncode.appdata.dir", temporaryDirectory.resolve("appdata").toString());
        Database.initDatabase();
    }

    @AfterEach void tearDown() { System.clearProperty("vncode.appdata.dir"); }

    @Test void sharedRendererScansBothCodesWithGs1SeparatorsAtThermalPrinterResolutions() throws Exception {
        Path labels = temporaryDirectory.resolve("combined.pdf");
        try (PdfDocument document = new PdfDocument(new PdfWriter(labels.toFile()))) {
            OzonCombinedLabelRenderer.append(document, item(), product(false), "\u001d" + KIZ);
        }
        try (PDDocument document = Loader.loadPDF(labels.toFile())) {
            assertEquals(1, document.getNumberOfPages());
            assertEquals(58, document.getPage(0).getMediaBox().getWidth() * 25.4 / 72, 0.1);
            assertEquals(40, document.getPage(0).getMediaBox().getHeight() * 25.4 / 72, 0.1);
            for (int dpi : new int[] {203, 300}) {
                Result matrix = decode(document, 0, dpi, BarcodeFormat.DATA_MATRIX);
                assertEquals(KizService.scannerSafeCode(KIZ), KizService.scannerSafeCode(matrix.getText()));
                assertEquals("]d2", matrix.getResultMetadata().get(ResultMetadataType.SYMBOLOGY_IDENTIFIER));
                assertEquals(BARCODE, decode(document, 0, dpi, BarcodeFormat.CODE_128).getText());
            }
        }
    }

    @Test void unmarkedProductBarcodePagesHaveNoKizAndStillScan() throws Exception {
        Path labels = temporaryDirectory.resolve("unmarked.pdf");
        try (PdfDocument document = new PdfDocument(new PdfWriter(labels.toFile()))) {
            OzonCombinedLabelRenderer.append(document, item(), product(false), null);
            OzonCombinedLabelRenderer.append(document, item(), product(false), "");
        }
        try (PDDocument document = Loader.loadPDF(labels.toFile())) {
            assertEquals(2, document.getNumberOfPages());
            assertFalse(new PDFTextStripper().getText(document).contains("KIZ"));
            for (int page = 0; page < 2; page++) {
                for (int dpi : new int[] {203, 300}) {
                    assertEquals(BARCODE, decode(document, page, dpi, BarcodeFormat.CODE_128).getText());
                }
            }
        }
    }

    @Test void archivedFrozenPrintSnapshotRendersWithoutAnyCurrentCatalogRow() throws Exception {
        assertTrue(new OzonCatalogRepository().findAll(1).isEmpty());
        Path labels = temporaryDirectory.resolve("frozen-history.pdf");
        try (PdfDocument document = new PdfDocument(new PdfWriter(labels.toFile()))) {
            OzonCombinedLabelRenderer.append(document, item(), product(true), KIZ);
        }
        try (PDDocument document = Loader.loadPDF(labels.toFile())) {
            assertEquals(BARCODE, decode(document, 0, 203, BarcodeFormat.CODE_128).getText());
            assertEquals(KizService.scannerSafeCode(KIZ), KizService.scannerSafeCode(
                    decode(document, 0, 203, BarcodeFormat.DATA_MATRIX).getText()));
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("152DASH-52"));
            assertTrue(text.contains("Коричневый"));
            assertTrue(text.contains("Size: 52"));
        }
    }

    private static OzonPostingItemDto item() {
        return new OzonPostingItemDto(0, "101", "101", "152DASH-52", "Костюм мужской", 1, "RUB", "100");
    }

    private static OzonProductDto product(boolean archived) {
        return new OzonProductDto("9001", "152DASH-52", "101", "Костюм мужской", "", "152DASH-52",
                "Коричневый", "52", archived, "", List.of("00000000000000", BARCODE));
    }

    private static Result decode(PDDocument document, int page, int dpi, BarcodeFormat format) throws Exception {
        BufferedImage image = new PDFRenderer(document).renderImageWithDPI(page, dpi, ImageType.GRAY);
        BufferedImage area;
        if (format == BarcodeFormat.DATA_MATRIX) {
            int width = Math.min(image.getWidth(), (int) Math.round(22 * dpi / 25.4));
            int height = Math.min(image.getHeight(), (int) Math.round(27 * dpi / 25.4));
            area = image.getSubimage(0, 0, width, height);
        } else {
            int top = (int) Math.round(27.5 * dpi / 25.4);
            area = image.getSubimage(0, top, image.getWidth(), image.getHeight() - top);
        }
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                new BufferedImageLuminanceSource(area))), Map.of(DecodeHintType.POSSIBLE_FORMATS,
                List.of(format), DecodeHintType.TRY_HARDER, true));
    }
}
