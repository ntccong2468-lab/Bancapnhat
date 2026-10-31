package com.vncode.app.features.print;

import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.models.Sticker;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WbReshipmentLabelServiceTest {
    @TempDir Path temp;

    @Test void savesOneCorrectWbShippingLabelAsOnePagePdf() throws Exception {
        var calls = new AtomicInteger();
        String image = png();
        var service = new WbReshipmentLabelService((token, ids) -> {
            calls.incrementAndGet();
            assertEquals("wb-test-token", token);
            assertEquals(List.of(173L), ids);
            return List.of(new Sticker(173L, 1L, 2L, "barcode", image));
        }, s -> {});
        Path file = temp.resolve("label.pdf");
        service.export(new Shop(1, "WB", "wb-test-token"), 173, file);
        assertEquals(1, calls.get());
        try (var pdf = Loader.loadPDF(file.toFile())) {
            assertEquals(1, pdf.getNumberOfPages());
            var page = pdf.getPage(0).getMediaBox();
            assertEquals(58 * 72f / 25.4f, page.getWidth(), 0.01f);
            assertEquals(40 * 72f / 25.4f, page.getHeight(), 0.01f);
        }
    }

    @Test void rejectsWrongOrderWithoutReplacingExistingFile() throws Exception {
        Path file = temp.resolve("existing.pdf");
        Files.writeString(file, "existing");
        var service = new WbReshipmentLabelService((token, ids) ->
                List.of(new Sticker(174L, 1L, 2L, "barcode", png())), s -> {});
        assertThrows(IOException.class, () -> service.export(new Shop(1, "WB", "token"), 173, file));
        assertEquals("existing", Files.readString(file));
    }

    @Test void rejectsMissingDuplicateAndCorruptedStickers() throws Exception {
        Shop shop = new Shop(1, "WB", "token");
        Path file = temp.resolve("label.pdf");
        assertThrows(IOException.class, () -> new WbReshipmentLabelService((t, ids) -> List.of(), s -> {})
                .export(shop, 173, file));
        assertThrows(IOException.class, () -> new WbReshipmentLabelService((t, ids) ->
                List.of(new Sticker(173L, 1L, 2L, "", png()), new Sticker(173L, 1L, 2L, "", png())), s -> {})
                .export(shop, 173, file));
        assertThrows(IOException.class, () -> new WbReshipmentLabelService((t, ids) ->
                List.of(new Sticker(173L, 1L, 2L, "", "not-a-png")), s -> {})
                .export(shop, 173, file));
        assertFalse(Files.exists(file));
    }

    @Test void rejectsOzonBeforeRequest() {
        var calls = new AtomicInteger();
        var service = new WbReshipmentLabelService((token, ids) -> { calls.incrementAndGet(); return List.of(); }, s -> {});
        var ozon = new Shop(1, "Ozon", Marketplace.OZON, "client", "token");
        assertThrows(IllegalStateException.class, () -> service.export(ozon, 173, temp.resolve("a.pdf")));
        assertEquals(0, calls.get());
    }

    @Test void changingShopAfterRequestNeverPublishesLabel() throws Exception {
        Path target = temp.resolve("label.pdf");
        Files.writeString(target, "old");
        var checks = new AtomicInteger();
        var service = new WbReshipmentLabelService((t, ids) ->
                List.of(new Sticker(173L, 1L, 2L, "", png())), s -> {
                    if (checks.incrementAndGet() == 2) throw new SecurityException("changed shop");
                });
        assertThrows(SecurityException.class, () -> service.export(new Shop(1, "WB", "token"), 173, target));
        assertEquals("old", Files.readString(target));
    }

    private static String png() throws IOException {
        BufferedImage img = new BufferedImage(58, 40, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "png", output));
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }
}
