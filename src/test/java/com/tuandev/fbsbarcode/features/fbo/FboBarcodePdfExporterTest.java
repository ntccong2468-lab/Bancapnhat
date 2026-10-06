package com.tuandev.fbsbarcode.features.fbo;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.print.PrintTemplateService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FboBarcodePdfExporterTest {
    @TempDir Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void exportsTwoBarcodeLabelsThenOneSeparateKizLabelAt58x40() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        FboProductSku product = new FboProductSku(
                101, "ART-42", "Jacket", "Brand", "Jacket black", "Black", "42", "42",
                "4601234567890", "", true, "OZON-SKU-42");
        FboPrintPlan plan = new FboPrintPlan(List.of(
                FboPrintPage.barcode(product, 1),
                FboPrintPage.barcode(product, 1),
                FboPrintPage.kiz(product, "010460123456789021SERIAL-42", 1)), List.of());
        Path output = tempDir.resolve("ozon-fbo-58x40.pdf");

        new FboBarcodePdfExporter().exportPlan(plan, output.toFile());

        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertEquals(3, document.getNumberOfPages());
            document.getPages().forEach(page -> {
                assertEquals(PrintTemplateService.PAGE_WIDTH, page.getMediaBox().getWidth(), 0.2d);
                assertEquals(PrintTemplateService.PAGE_HEIGHT, page.getMediaBox().getHeight(), 0.2d);
            });
            String first = pageText(document, 1);
            String second = pageText(document, 2);
            String third = pageText(document, 3);
            assertTrue(first.contains("4601234567890"));
            assertTrue(second.contains("4601234567890"));
            assertFalse(third.contains("4601234567890"));
            assertTrue(third.contains("ART-42"));
            assertTrue(third.contains("Jacket"));
        }
    }

    private static String pageText(PDDocument document, int page) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(document).replaceAll("\\s+", " ").trim();
    }
}
