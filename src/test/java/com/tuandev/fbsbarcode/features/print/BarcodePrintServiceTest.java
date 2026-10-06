package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.models.Order;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BarcodePrintServiceTest {
    @Test
    void shouldExportTemplateAndStickerPdf() throws Exception {
        Order order = new Order();
        order.setId(123456L);
        order.setBrand("Brand");
        order.setName("Ao thun the thao");
        order.setSubjectName("Ao");
        order.setColor("Den");
        order.setArticle("ART-001");
        order.setSize("L");
        order.setBarcode("8938501432101");
        order.setSticker("ABCD 12");
        order.setStickerCode("WB-STICKER-CODE");
        order.setKiz("0104607061657218215sQ>GQj5L8qP91Q");

        Path file = Files.createTempFile("barcode-template-", ".pdf");
        new BarcodePrintService().export(new PrintTemplateService().createSystemDefaultTemplate("Test"), List.of(order), file.toFile());

        assertTrue(Files.size(file) > 0);
        try (PdfDocument pdf = new PdfDocument(new PdfReader(file.toFile()))) {
            assertEquals(2, pdf.getNumberOfPages());
            assertEquals(PrintTemplateService.PAGE_WIDTH, pdf.getPage(1).getPageSize().getWidth(), 0.2d);
            assertEquals(PrintTemplateService.PAGE_HEIGHT, pdf.getPage(1).getPageSize().getHeight(), 0.2d);
        }
        file.toFile().deleteOnExit();
    }

    @Test
    void shouldRenderFullProductNameField() throws Exception {
        String productName = "Ao thun the thao nam cotton co tron mau den";
        Order order = new Order();
        order.setId(123456L);
        order.setName(productName);
        order.setBarcode("8938501432101");
        order.setSticker("ABCD 12");
        order.setStickerCode("WB-STICKER-CODE");
        order.setKiz("0104607061657218215sQ>GQj5L8qP91Q");

        PrintTemplate template = new PrintTemplateService().createSystemDefaultTemplate("Name field");
        template.getElements().removeIf(element -> element.getType() == PrintElementType.TEXT_FIELD
                && element.getFieldKey() != PrintFieldKey.NAME);
        template.getElements().removeIf(element -> element.getType() == PrintElementType.BARCODE_CODE128
                || element.getType() == PrintElementType.STICKER_TAIL);
        PrintTemplateElement nameElement = template.getElements().stream()
                .filter(element -> element.getType() == PrintElementType.TEXT_FIELD && element.getFieldKey() == PrintFieldKey.NAME)
                .findFirst()
                .orElseGet(() -> {
                    PrintTemplateElement created = PrintTemplateElement.create(PrintElementType.TEXT_FIELD, "Name", 4, 4, PrintTemplateService.PAGE_WIDTH - 8, 42);
                    created.setFieldKey(PrintFieldKey.NAME);
                    created.setZIndex(2);
                    template.getElements().add(created);
                    return created;
                });
        nameElement.setX(4);
        nameElement.setY(4);
        nameElement.setWidth(PrintTemplateService.PAGE_WIDTH - 8);
        nameElement.setHeight(42);
        nameElement.setFontSize(8);
        nameElement.setZIndex(2);

        Path file = Files.createTempFile("barcode-full-name-", ".pdf");
        GenerateBarcode.exportTemplatePages(template, List.of(order), file.toFile());

        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            String text = new PDFTextStripper().getText(document).replaceAll("\\s+", " ").trim();
            assertTrue(text.contains(productName), "PDF text should contain full product name, got: " + text);
        }
        file.toFile().deleteOnExit();
    }

    @Test
    void shouldRenderRuSizeField() throws Exception {
        Order order = new Order();
        order.setRuSize("44-46");
        order.setBarcode("8938501432101");

        PrintTemplate template = new PrintTemplateService().createSystemDefaultTemplate("RU size field");
        template.getElements().removeIf(element -> element.getType() == PrintElementType.TEXT_FIELD
                && element.getFieldKey() != PrintFieldKey.RU_SIZE);
        PrintTemplateElement ruSizeElement = PrintTemplateElement.create(PrintElementType.TEXT_FIELD, "RU size", 4, 4, 80, 16);
        ruSizeElement.setFieldKey(PrintFieldKey.RU_SIZE);
        ruSizeElement.setPrefix("RU");
        ruSizeElement.setFontSize(8);
        ruSizeElement.setZIndex(1);
        template.getElements().add(ruSizeElement);

        Path file = Files.createTempFile("barcode-ru-size-", ".pdf");
        GenerateBarcode.exportTemplatePages(template, List.of(order), file.toFile());

        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            String text = new PDFTextStripper().getText(document).replaceAll("\\s+", " ").trim();
            assertTrue(text.contains("RU: 44-46"), "PDF text should contain RU size, got: " + text);
        }
        file.toFile().deleteOnExit();
    }
}
