package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.*;

import com.vncode.app.config.Database;
import com.vncode.app.features.fbo.FboProductImageService;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonPickingListPdfExporterTest {
    @TempDir Path temp;

    @AfterEach void cleanup() { System.clearProperty("vncode.appdata.dir"); }

    @Test void startsIndependentImageReadsTogetherAndReusesRepeatedProductImage() throws Exception {
        System.setProperty("vncode.appdata.dir", temp.resolve("appdata").toString());
        Database.initDatabase();
        try (var connection = Database.getConnection(); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'shop','OZON','id','key')");
        }
        var products = java.util.stream.IntStream.range(0, 4).mapToObj(i -> new OzonProductDto(
                "product-" + i, "offer-" + i, "sku-" + i, "Product " + i,
                "https://example.test/" + i + ".png", "article-" + i, "", "", "", "", false, "", List.of(), "", "", "", "")).toList();
        new OzonCatalogRepository().upsertPage(1, products, "");
        var items = java.util.stream.IntStream.range(0, 48).map(i -> i < 4 ? i : 0).mapToObj(i -> new OzonPostingItemDto(
                i, "product-" + i, "sku-" + i, "offer-" + i, "Product " + i, 1, "RUB", "1")).toList();
        var posting = new OzonPostingDto("POST-1", "POST-1", "POST-1", "awaiting_deliver", "", "", "", "", "", "",
                new OzonRequirements(List.of(), List.of(), List.of()), List.of(), false, items);

        CountDownLatch started = new CountDownLatch(4);
        Map<String,CompletableFuture<byte[]>> reads = new ConcurrentHashMap<>();
        FboProductImageService imageLoader = new FboProductImageService() {
            @Override public CompletableFuture<byte[]> loadThumbnailBytes(String url) {
                var future = new CompletableFuture<byte[]>();
                assertNull(reads.putIfAbsent(url, future), "Each product URL should be scheduled once per window");
                started.countDown();
                return future;
            }
        };
        var target = temp.resolve("picking.pdf");
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var output = executor.submit(() -> {
                new OzonPickingListPdfExporter(imageLoader).export(target.toFile(),
                        new Shop(1, "shop", Marketplace.OZON, "id", "key"), posting);
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS), "Independent reads should start before waiting for first image");
            var bytes = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB), "png", bytes));
            reads.values().forEach(read -> read.complete(bytes.toByteArray()));
            output.get(10, TimeUnit.SECONDS);
            try (var pdf = Loader.loadPDF(target.toFile())) {
                String text = new PDFTextStripper().getText(pdf);
                assertTrue(text.indexOf("Product 0") < text.indexOf("Product 1"));
                assertTrue(text.indexOf("Product 1") < text.indexOf("Product 2"));
                assertTrue(pdf.getNumberOfPages() > 1, "Reuse must work across flushed PDF pages");
                int imageObjects = 0;
                var resources = pdf.getPage(0).getResources();
                for (var name : resources.getXObjectNames()) if (resources.getXObject(name) instanceof PDImageXObject) imageObjects++;
                assertEquals(4, imageObjects, "Repeated product image must use one PDF image object");
            }
        } finally {
            reads.values().forEach(read -> read.complete(null));
            executor.shutdownNow();
        }
    }
}
