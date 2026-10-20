package com.vncode.app.features.fbo;

import com.vncode.app.config.Database;
import com.vncode.app.features.print.PrintTemplateService;
import com.vncode.app.features.print.PrintTemplateElement;
import com.vncode.app.features.print.PrintElementType;
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
        System.clearProperty("vncode.appdata.dir");
    }

    @Test
    void exportsTheFrozenLayoutWithoutReloadingTheCurrentDefault() throws Exception {
        System.setProperty("vncode.appdata.dir", tempDir.toString());Database.initDatabase();
        var frozen=new FboPrintTemplateService().createSystemDefaultTemplate("Frozen");
        var marker=PrintTemplateElement.create(PrintElementType.STATIC_TEXT,"Frozen marker",8,8,140,20);marker.setContent("FROZEN-LAYOUT");frozen.setElements(List.of(marker));
        var changingDefaults=new FboPrintTemplateService(){@Override public com.vncode.app.features.print.PrintTemplate getDefaultTemplate(){throw new AssertionError("A journaled export must use its frozen layout");}};
        var product=new FboProductSku(101,"ART","Jacket","Brand","Jacket","Black","42","42","4601234567890","",false);
        var plan=new FboPrintPlan(List.of(FboPrintPage.barcode(product,1)),List.of());var published=new java.util.concurrent.atomic.AtomicInteger();
        var output=tempDir.resolve("frozen.pdf").toFile();new FboBarcodePdfExporter(changingDefaults).exportPlan(plan,frozen,output,published::incrementAndGet);
        assertEquals(1,published.get());
        try(var document=Loader.loadPDF(output)){assertEquals(1,document.getNumberOfPages());assertTrue(pageText(document,1).contains("FROZEN-LAYOUT"));assertEquals(PrintTemplateService.PAGE_WIDTH,document.getPage(0).getMediaBox().getWidth(),0.2);assertEquals(PrintTemplateService.PAGE_HEIGHT,document.getPage(0).getMediaBox().getHeight(),0.2);}
    }

    @Test
    void exportsTwoBarcodeLabelsThenOneSeparateKizLabelAt58x40() throws Exception {
        System.setProperty("vncode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        FboProductSku product = new FboProductSku(
                101, "ART-42", "Jacket", "Brand", "Jacket black", "Black", "42", "42",
                "4601234567890", "", true, "OZON-SKU-42").withVerifiedGtin("04610689132457");
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
            assertTrue(first.contains("04610689132457"));
            assertFalse(first.contains("4601234567890"));
            assertTrue(second.contains("04610689132457"));
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
