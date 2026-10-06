package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.models.Order;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderDetailsPdfExporterTest {
    @Test
    void shouldExportOrderDetailsPdf() throws Exception {
        Order order = new Order();
        order.setId(123456L);
        order.setSize("M");
        order.setColor("Black");
        order.setArticle("ART-001");
        order.setSticker("ABCD 12");

        Path file = Files.createTempFile("order-details-", ".pdf");
        new OrderDetailsPdfExporter().export(file.toFile(), List.of(order));
        assertTrue(Files.size(file) > 0);
        file.toFile().deleteOnExit();
    }

    @Test
    void shouldSkipUnsupportedJpegAndContinueExportingOrders() throws Exception {
        Order orderWithUnsupportedImage = order(101L, "ART-BAD");
        orderWithUnsupportedImage.setImage(unsupportedJpegSof7());
        Order followingOrder = order(202L, "ART-GOOD");

        Path file = Files.createTempFile("order-details-unsupported-jpeg-", ".pdf");
        try {
            new OrderDetailsPdfExporter().export(
                    file.toFile(),
                    List.of(orderWithUnsupportedImage, followingOrder)
            );

            try (PDDocument document = Loader.loadPDF(file.toFile())) {
                String text = new PDFTextStripper().getText(document);
                assertTrue(text.contains("101"));
                assertTrue(text.contains("202"));
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private Order order(long id, String article) {
        Order order = new Order();
        order.setId(id);
        order.setSize("M");
        order.setColor("Black");
        order.setArticle(article);
        order.setSticker("ABCD 12");
        return order;
    }

    private byte[] unsupportedJpegSof7() {
        return new byte[]{
                (byte) 0xff, (byte) 0xd8,
                (byte) 0xff, (byte) 0xc7,
                0, 17,
                8,
                0, 1,
                0, 1,
                3,
                1, 17, 0,
                2, 17, 0,
                3, 17, 0,
                (byte) 0xff, (byte) 0xd9
        };
    }
}
