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

    @Test void eachPickingRowShowsThePostingOfItsOwnProductEvenWhenSkusRepeat() throws Exception {
        System.setProperty("vncode.appdata.dir", temp.resolve("appdata").toString());
        Database.initDatabase();
        try (var connection = Database.getConnection(); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'shop','OZON','id','key')");
        }
        var first = new OzonPostingDto("70000000-001-1", "order-1", "order-1", "awaiting_deliver", "", "", "", "", "", "",
                new OzonRequirements(List.of(), List.of(), List.of()), List.of(), false, List.of(
                new OzonPostingItemDto(0, "product-a", "sku-a", "article-a", "UniqueAlpha", 1, "RUB", "1"),
                new OzonPostingItemDto(1, "product-b", "sku-b", "article-b", "UniqueBeta", 2, "RUB", "2")));
        var second = new OzonPostingDto("70000000-002-1", "order-2", "order-2", "awaiting_deliver", "", "", "", "", "", "",
                new OzonRequirements(List.of(), List.of(), List.of()), List.of(), false, List.of(
                new OzonPostingItemDto(0, "product-c", "sku-c", "article-c", "UniqueGamma", 1, "RUB", "3"),
                new OzonPostingItemDto(1, "product-b", "sku-b", "article-b", "UniqueBeta", 1, "RUB", "2")));

        var target = temp.resolve("batch-picking.pdf");
        new OzonPickingListPdfExporter().exportBatch(target.toFile(),
                new Shop(1, "shop", Marketplace.OZON, "id", "key"), List.of(first, second));
        try (var pdf = Loader.loadPDF(target.toFile())) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(pdf);
            String missing = com.vncode.app.shared.I18nService.getInstance().tr("ozon.picking.tracking.missing");
            assertTrue(text.replaceAll("\\s+", "").contains(missing.replaceAll("\\s+", "")),
                    "A missing tracking observation should be visible as missing data");
            var rows = text.lines().filter(line -> line.contains("70000000-")).toList();
            assertEquals(4, rows.size(), "Each item, including repeated SKU, needs its own posting number: " + text);
            assertTrue(rows.get(0).contains("70000000-001-1") && rows.get(0).contains("UniqueAlpha"), text);
            assertTrue(rows.get(1).contains("70000000-001-1") && rows.get(1).contains("UniqueBeta"), text);
            assertTrue(rows.get(2).contains("70000000-002-1") && rows.get(2).contains("UniqueGamma"), text);
            assertTrue(rows.get(3).contains("70000000-002-1") && rows.get(3).contains("UniqueBeta"), text);
        }
    }

    @Test void longPostingNumberRemainsReadableInsideThePage() throws Exception {
        System.setProperty("vncode.appdata.dir", temp.resolve("appdata").toString());
        Database.initDatabase();
        try (var connection = Database.getConnection(); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'shop','OZON','id','key')");
        }
        String postingNumber = "1234567890".repeat(25) + "123456";
        var posting = new OzonPostingDto(postingNumber, "order", "order", "awaiting_deliver", "", "", "", "", "", "",
                new OzonRequirements(List.of(), List.of(), List.of()), List.of(), false,
                List.of(new OzonPostingItemDto(0, "product", "sku", "article", "Long identifier", 1, "RUB", "1")));
        var target = temp.resolve("long-posting.pdf");
        new OzonPickingListPdfExporter().export(target.toFile(),
                new Shop(1, "shop", Marketplace.OZON, "id", "key"), posting);
        try (var pdf = Loader.loadPDF(target.toFile())) {
            float pageWidth = pdf.getPage(0).getCropBox().getWidth();
            var stripper = new PDFTextStripper() {
                @Override protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> positions) throws java.io.IOException {
                    for (var position : positions) {
                        assertTrue(position.getXDirAdj() >= 0 && position.getXDirAdj() + position.getWidthDirAdj() <= pageWidth,
                                "Text must stay inside the page");
                    }
                    super.writeString(text, positions);
                }
            };
            String text = stripper.getText(pdf).replaceAll("\\s+", "");
            assertTrue(text.contains(postingNumber), "The full posting number must be present in the PDF: " + text);
        }
    }

    @Test void showsOzonTrackingNumberFromSyncedPostingWithoutConfusingItWithPostingNumber() throws Exception {
        System.setProperty("vncode.appdata.dir", temp.resolve("appdata").toString());
        Database.initDatabase();
        try (var connection = Database.getConnection(); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'shop','OZON','client-one','key')");
        }
        var response = com.google.gson.JsonParser.parseString("""
                {"result":{"posting_number":"70000000-001-1","tracking_number":"TRACK-SYNTHETIC-123",
                "status":"awaiting_deliver","products":[{"sku":"11","offer_id":"article-a","name":"Product A","quantity":1}]}}
                """).getAsJsonObject();
        var repository = new OzonPostingRepository();
        var shop = new Shop(1, "shop", Marketplace.OZON, "client-one", "key");
        repository.upsertDetail(1, OzonJson.parsePostingDetail(response).withTrackingOwner(
                com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop)));
        var posting = repository.findMany(1, List.of("70000000-001-1")).get("70000000-001-1");
        var target = temp.resolve("tracking-picking.pdf");
        new OzonPickingListPdfExporter().export(target.toFile(), shop, posting);
        try (var pdf = Loader.loadPDF(target.toFile())) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("70000000-001-1"), text);
            assertTrue(text.replaceAll("\\s+", "").contains("TRACK-SYNTHETIC-123"),
                    "Ozon tracking and posting numbers must both be shown: " + text);
        }
        response.getAsJsonObject("result").remove("tracking_number");
        repository.upsertDetail(1, OzonJson.parsePostingDetail(response).withTrackingOwner(
                com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop)));
        var stale = temp.resolve("tracking-stale.pdf");
        new OzonPickingListPdfExporter().export(stale.toFile(), shop,
                repository.findMany(1, List.of("70000000-001-1")).get("70000000-001-1"));
        try (var pdf = Loader.loadPDF(stale.toFile())) {
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", "");
            String expectedLabel = com.vncode.app.shared.I18nService.getInstance().tr("ozon.picking.tracking.stale").replaceAll("\\s+", "");
            assertTrue(text.contains("TRACK-SYNTHETIC-123") && text.contains(expectedLabel),
                    "Previously saved tracking must be clearly marked for verification");
        }
    }

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
