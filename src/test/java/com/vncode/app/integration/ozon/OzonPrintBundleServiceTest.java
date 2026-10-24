package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.datamatrix.DataMatrixReader;
import com.google.zxing.oned.Code128Reader;
import com.itextpdf.barcodes.Barcode128;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.KizService;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseGate;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.print.history.ImageCacheRepository;
import com.vncode.app.features.print.history.PrintHistoryService;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.I18nService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonPrintBundleServiceTest {
    private static final float LABEL_WIDTH = (float) (58 * 72d / 25.4d);
    private static final float LABEL_HEIGHT = (float) (40 * 72d / 25.4d);
    private static final String RAW_KIZ = "010464558878115421SERIAL-0001\u001d91ABCD\u001d92SIGNATURE";
    private static final String PRODUCT_BARCODE = "OZN0000003583";

    @TempDir
    Path temporaryDirectory;

    private Shop shop;
    private Path officialPdf;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("vncode.appdata.dir", temporaryDirectory.resolve("appdata").toString());
        Database.initDatabase();
        shop = new Shop(1, "Ozon", Marketplace.OZON, "client-1", "secret");
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,?,?,?,?)")) {
            statement.setString(1, shop.getName());
            statement.setString(2, Marketplace.OZON.name());
            statement.setString(3, shop.getClientId());
            statement.setString(4, shop.getApiKey());
            statement.executeUpdate();
        }
        officialPdf = temporaryDirectory.resolve("official.pdf");
        writeOfficialTwoPagePdf(officialPdf);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("vncode.appdata.dir");
    }

    @Test
    void deniedNewPrintDoesNotConsumeReservedKizOrCreatePdf() throws Exception {
        seedPosting(1);
        seedValidatedExemplar(RAW_KIZ);
        var prior = LicenseRuntime.service();
        Path target = temporaryDirectory.resolve("denied-labels.pdf");
        try (var denied = LicenseDomainFixture.denied(temporaryDirectory.resolve("denied"))) {
            LicenseRuntime.install(denied);
            AtomicBoolean prepared = new AtomicBoolean(false);
            assertThrows(LicenseGate.LicenseDeniedException.class, () -> service(prepared).export(
                    shop, "POST-1", target.toFile(), temporaryDirectory.resolve("denied-picking.pdf").toFile()));
            assertFalse(prepared.get());
            assertFalse(Files.exists(target));
            assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes WHERE id=100"));
            assertEquals("VALIDATED", scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51"));
            assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
        } finally {
            LicenseRuntime.install(prior);
        }
    }

    @Test
    void rightsLostDuringOfficialDownloadDoNotConsumeKizOrPublishNewPrint() throws Exception {
        seedPosting(1);
        seedValidatedExemplar(RAW_KIZ);
        var prior = LicenseRuntime.service();
        Path target = temporaryDirectory.resolve("expired-during-download.pdf");
        try (var denied = LicenseDomainFixture.denied(temporaryDirectory.resolve("denied"))) {
            var service = new OzonPrintBundleService(
                    new OzonPostingRepository(), new OzonExemplarJobRepository(),
                    (selectedShop, postingNumber) -> { throw new AssertionError("Existing VALIDATED job must be reused"); },
                    (selectedShop, postingNumber, officialTarget) -> {
                        Files.copy(officialPdf, officialTarget.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        LicenseRuntime.install(denied);
                        return officialTarget;
                    });
            assertThrows(LicenseGate.LicenseDeniedException.class, () -> service.export(
                    shop, "POST-1", target.toFile(), temporaryDirectory.resolve("expired-picking.pdf").toFile()));
            assertFalse(Files.exists(target));
            assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes WHERE id=100"));
            assertEquals("VALIDATED", scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51"));
        } finally {
            LicenseRuntime.install(prior);
        }
    }

    @Test
    void frozenPreviouslyPrintedKizCanBeReprintedWithDeniedLicense() throws Exception {
        seedPosting(1);
        seedAcceptedExemplars(List.of(RAW_KIZ));
        var prior = LicenseRuntime.service();
        try (var denied = LicenseDomainFixture.denied(temporaryDirectory.resolve("denied"))) {
            LicenseRuntime.install(denied);
            AtomicBoolean prepared = new AtomicBoolean(false);
            var result = service(prepared).export(shop, "POST-1",
                    temporaryDirectory.resolve("old-labels.pdf").toFile(),
                    temporaryDirectory.resolve("old-picking.pdf").toFile());
            assertFalse(prepared.get());
            assertEquals(1, result.kizPages());
            assertEquals("ACCEPTED", scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51"));
        } finally {
            LicenseRuntime.install(prior);
        }
    }

    @Test
    void acceptedSingleUnitProducesOnlyShippingAndCombinedLabelsAndSeparatePickingPdf() throws Exception {
        seedPosting(1);
        seedAcceptedExemplars(List.of(RAW_KIZ));
        AtomicBoolean prepared = new AtomicBoolean(false);
        Path labels = temporaryDirectory.resolve("OZON-POST-1.pdf");
        Path picking = temporaryDirectory.resolve("OZON-POST-1-picking.pdf");

        OzonPrintBundleService.ExportResult result = service(prepared).export(
                shop, "POST-1", labels.toFile(), picking.toFile());

        assertFalse(prepared.get(), "An accepted durable job must not push KIZ again while reprinting");
        assertEquals(1, result.officialPages());
        assertEquals(1, result.kizPages());
        assertEquals(2, result.totalPages());
        try (PDDocument document = Loader.loadPDF(labels.toFile())) {
            assertEquals(2, document.getNumberOfPages());
            assertPageMillimeters(document, 0, 58, 40);
            assertPageMillimeters(document, 1, 58, 40);
            for (float dpi : new float[] {203, 300}) {
                Result matrix = decodeRenderedDataMatrixResult(document, 1, dpi);
                assertEquals(KizService.scannerSafeCode(RAW_KIZ), KizService.scannerSafeCode(matrix.getText()));
                assertEquals("]d2", matrix.getResultMetadata().get(ResultMetadataType.SYMBOLOGY_IDENTIFIER));
                assertEquals(PRODUCT_BARCODE, decodeProductBarcode(document, 1, dpi));
            }
            try (PDDocument official = Loader.loadPDF(officialPdf.toFile())) {
                assertArrayEquals(official.getPage(0).getContents().readAllBytes(),
                        document.getPage(0).getContents().readAllBytes());
            }
            assertFalse(new PDFTextStripper().getText(document).contains("Official Ozon product label"));
            String kizText = pageText(document, 1);
            assertTrue(kizText.contains("Men's sports suit"));
            assertTrue(kizText.contains("Article: seller-article"));
            assertTrue(kizText.contains("Màu: deep black"));
            assertTrue(kizText.contains("Size: XL"));
            assertTrue(kizText.contains(PRODUCT_BARCODE));
            assertFalse(kizText.contains(shop.getName()), "The KIZ label must not print a shop/brand heading");
            assertFalse(kizText.contains("OZON - KIZ"));
            assertFalse(kizText.contains("KIZ accepted by Ozon"));
            assertFalse(kizText.contains("Item "), "The KIZ label must not print item counters");
            assertFalse(kizText.contains("Unit "), "The KIZ label must not print unit counters");
            assertEquals(1, pageOperators(document, 1).stream().filter("S"::equals).count(),
                    "Only the horizontal separator is stroked; the label has no outer border");
            assertEquals(0, imageCount(document, 1), "The DataMatrix must be vector content, not a bitmap image");
        }
        try (PDDocument document = Loader.loadPDF(picking.toFile())) {
            assertEquals(1, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertFalse(text.contains("OZON FBS - PICKING LIST"));
            assertFalse(text.contains("POST-1"));
            assertFalse(text.contains("100001"));
            assertFalse(text.contains("Posting:"));
            assertFalse(text.contains("Shop:"));
            assertFalse(text.contains("Shipment:"));
            assertFalse(text.contains("Printed:"));
            assertFalse(text.contains("Raw KIZ values are intentionally omitted"));
            assertFalse(text.contains("KIZ"));
            assertPickingColumnOrder(text);
            assertTrue(text.contains("Men's sports suit"));
            assertTrue(text.contains("seller-article"));
            assertFalse(text.contains("deep black"));
            assertFalse(text.contains("XL"));
            assertFalse(text.contains("SKU-3583"));
            assertTrue(text.contains("1"));
            assertFalse(text.contains(RAW_KIZ), "The picking list must not expose raw KIZ");
            assertTrue(imageCount(document, 0) >= 1, "The picking list must embed the catalog product image");
            assertPageMillimeters(document, 0, 210, 297);
        }
        assertEquals("1",scalar("SELECT count(*) FROM ozon_fbs_print_receipts WHERE shop_id=1 AND posting_number='POST-1'"));
    }

    @Test
    void appendsOneKizPageForEveryAcceptedExemplar() throws Exception {
        String secondKiz = "010464558878115421SERIAL-0002\u001d91ABCD\u001d92SIGNATURE";
        seedPosting(2);
        seedAcceptedExemplars(List.of(RAW_KIZ, secondKiz));
        Path labels = temporaryDirectory.resolve("labels-two.pdf");
        Path picking = temporaryDirectory.resolve("picking-two.pdf");

        OzonPrintBundleService.ExportResult result = service(new AtomicBoolean()).export(
                shop, "POST-1", labels.toFile(), picking.toFile());

        assertEquals(1, result.officialPages());
        assertEquals(3, result.totalPages());
        assertEquals(2, result.kizPages());
        try (PDDocument document = Loader.loadPDF(labels.toFile())) {
            assertEquals(3, document.getNumberOfPages());
            assertEquals(KizService.scannerSafeCode(RAW_KIZ),
                    KizService.scannerSafeCode(decodeRenderedDataMatrixResult(document, 1, 300).getText()));
            assertEquals(KizService.scannerSafeCode(secondKiz),
                    KizService.scannerSafeCode(decodeRenderedDataMatrixResult(document, 2, 300).getText()));
            assertEquals(PRODUCT_BARCODE, decodeProductBarcode(document, 1, 203));
            assertEquals(PRODUCT_BARCODE, decodeProductBarcode(document, 2, 203));
        }
    }

    @Test
    void missingProductBarcodeKeepsExistingFilesAndReservedKiz() throws Exception {
        seedPosting(1);
        seedValidatedExemplar(RAW_KIZ);
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM ozon_product_barcodes");
        }
        assertBarcodeFailureKeepsFilesAndReservation("OZON_LABEL_BARCODE_MISSING");
    }

    @Test
    void ambiguousProductBarcodeKeepsExistingFilesAndReservedKiz() throws Exception {
        seedPosting(1);
        seedValidatedExemplar(RAW_KIZ);
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO ozon_product_barcodes(shop_id,product_id,barcode) "
                    + "VALUES(1,'1001','OZN0000009999')");
        }
        assertBarcodeFailureKeepsFilesAndReservation("OZON_LABEL_BARCODE_AMBIGUOUS");
    }

    @Test
    void archivedProductReprintUsesItsActualCachedBarcodeWithoutNewKizOrApiCalls() throws Exception {
        seedPosting(1);
        seedAcceptedExemplars(List.of(RAW_KIZ));
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE ozon_products SET archived=1 WHERE shop_id=1 AND product_id='1001'");
        }
        assertTrue(new OzonCatalogRepository().findBySkus(1, List.of("SKU-3583")).isEmpty(),
                "General product lookup must continue to exclude archived products");
        AtomicBoolean prepared = new AtomicBoolean();
        Path labels = temporaryDirectory.resolve("archived-labels.pdf");
        service(prepared).export(shop, "POST-1", labels.toFile(),
                temporaryDirectory.resolve("archived-picking.pdf").toFile());
        assertFalse(prepared.get());
        assertEquals("CONSUMED", scalar("SELECT status FROM kiz_codes WHERE id=100"));
        assertEquals("1", scalar("SELECT count(*) FROM kiz_codes"));
        try (PDDocument document = Loader.loadPDF(labels.toFile())) {
            assertEquals(PRODUCT_BARCODE, decodeProductBarcode(document, 1, 203));
        }
    }

    @Test
    void barcodeTooDenseForThermalPrinterCannotPublishUnreadableLabel() throws Exception {
        seedPosting(1);
        seedValidatedExemplar(RAW_KIZ);
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM ozon_product_barcodes");
            statement.execute("INSERT INTO ozon_product_barcodes(shop_id,product_id,barcode) "
                    + "VALUES(1,'1001','OZN" + "1".repeat(100) + "')");
        }
        assertBarcodeFailureKeepsFilesAndReservation("OZON_LABEL_BARCODE_TOO_DENSE");
    }

    @Test
    void staleExemplarIdentityCannotPrintAnotherProductsBarcode() throws Exception {
        seedPosting(1);
        seedValidatedExemplar(RAW_KIZ);
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE ozon_exemplars SET product_id='different-product'");
        }
        assertBarcodeFailureKeepsFilesAndReservation("OZON_LABEL_BINDING_MISMATCH");
    }

    @Test
    void longNumericOzonBarcodeAndCyrillicProductDecodeOnThermalPrinters() throws Exception {
        seedPosting(1);
        seedAcceptedExemplars(List.of(RAW_KIZ));
        String barcode = "OZN12345678901234567890";
        new OzonCatalogRepository().upsertPage(1, List.of(new OzonProductDto(
                "1001", "offer-black-176", "SKU-3583", "Костюм спортивный мужской утеплённый", "",
                "152DASH-52", "Коричневый", "52", false, "", List.of(barcode))), "");
        Path labels = temporaryDirectory.resolve("cyrillic-labels.pdf");
        service(new AtomicBoolean()).export(shop, "POST-1", labels.toFile(),
                temporaryDirectory.resolve("cyrillic-picking.pdf").toFile());
        try (PDDocument document = Loader.loadPDF(labels.toFile())) {
            for (float dpi : new float[] {203, 300}) {
                assertEquals(barcode, decodeProductBarcode(document, 1, dpi));
                assertEquals(KizService.scannerSafeCode(RAW_KIZ),
                        KizService.scannerSafeCode(decodeRenderedDataMatrixResult(document, 1, dpi).getText()));
            }
            assertTrue(pageText(document, 1).contains("Коричневый"));
            assertTrue(pageText(document, 1).contains("152DASH-52"));
            assertTrue(pageText(document, 1).contains("Size: 52"));
        }
    }

    private void assertBarcodeFailureKeepsFilesAndReservation(String code) throws Exception {
        Path labels = temporaryDirectory.resolve("unchanged-labels.pdf");
        Path picking = temporaryDirectory.resolve("unchanged-picking.pdf");
        Files.writeString(labels, "previous labels");
        Files.writeString(picking, "previous picking");
        IOException failure = assertThrows(IOException.class, () -> service(new AtomicBoolean()).export(
                shop, "POST-1", labels.toFile(), picking.toFile()));
        assertTrue(failure.getMessage().contains(code), failure.getMessage());
        assertTrue(failure.getMessage().contains("SKU-3583"));
        assertEquals("previous labels", Files.readString(labels));
        assertEquals("previous picking", Files.readString(picking));
        assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes WHERE id=100"));
        assertEquals("VALIDATED", scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51"));
    }

    @Test
    void locallyReservedKizIsConsumedOnlyAfterThePdfIsPublished() throws Exception {
        seedPosting(1);
        seedValidatedExemplar(RAW_KIZ);
        AtomicBoolean prepared = new AtomicBoolean(false);
        Path labels = temporaryDirectory.resolve("validated-labels.pdf");
        Path picking = temporaryDirectory.resolve("validated-picking.pdf");

        OzonPrintBundleService.ExportResult result = service(prepared).export(
                shop, "POST-1", labels.toFile(), picking.toFile());

        assertFalse(prepared.get(), "A locally reserved KIZ must print without any Ozon exemplar request");
        assertEquals(1, result.officialPages());
        assertEquals(1, result.kizPages());
        assertEquals(2, result.totalPages());
        assertTrue(Files.isRegularFile(labels));
        assertTrue(Files.isRegularFile(picking));
        assertEquals("CONSUMED", scalar("SELECT status FROM kiz_codes WHERE id=100"));
        assertEquals("ACCEPTED", scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51"));
        assertEquals("printed", scalar("SELECT check_status FROM ozon_exemplars WHERE job_id=51"));
        try (PDDocument document = Loader.loadPDF(labels.toFile())) {
            assertEquals(KizService.scannerSafeCode(RAW_KIZ),
                    KizService.scannerSafeCode(decodeRenderedDataMatrixResult(document, 1, 300).getText()));
        }
    }

    @Test
    void failedPdfExportKeepsTheKizReservedForRetry() throws Exception {
        seedPosting(1);
        seedValidatedExemplar(RAW_KIZ);
        OzonPrintBundleService service = new OzonPrintBundleService(
                new OzonPostingRepository(),
                new OzonExemplarJobRepository(),
                (selectedShop, postingNumber) -> {
                    throw new AssertionError("the existing reservation must be reused");
                },
                (selectedShop, postingNumber, target) -> {
                    throw new IOException("simulated label download failure");
                });

        assertThrows(IOException.class, () -> service.export(
                shop,
                "POST-1",
                temporaryDirectory.resolve("failed-labels.pdf").toFile(),
                temporaryDirectory.resolve("failed-picking.pdf").toFile()));

        assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes WHERE id=100"));
        assertEquals("VALIDATED", scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51"));
        assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }

    @Test
    void refusesToPublishWhenAutomaticPreparationDoesNotReachAccepted() throws Exception {
        seedPosting(1);
        Path labels = temporaryDirectory.resolve("blocked-labels.pdf");
        Path picking = temporaryDirectory.resolve("blocked-picking.pdf");
        AtomicBoolean labelDownloaded = new AtomicBoolean(false);
        OzonPrintBundleService service = new OzonPrintBundleService(
                new OzonPostingRepository(),
                new OzonExemplarJobRepository(),
                (selectedShop, postingNumber) ->
                        new OzonPreparationResult(postingNumber, "VERIFYING", 1, false, false, ""),
                (selectedShop, postingNumber, target) -> {
                    labelDownloaded.set(true);
                    return target;
                });

        IOException failure = assertThrows(IOException.class, () ->
                service.export(shop, "POST-1", labels.toFile(), picking.toFile()));

        assertTrue(failure.getMessage().contains("not ready for printing"));
        assertFalse(labelDownloaded.get());
        assertFalse(Files.exists(labels));
        assertFalse(Files.exists(picking));
    }

    @Test
    void mandatoryMarkCanNeverBeDowngradedToNotRequiredByThePrintFlow() throws Exception {
        seedPosting(1, true);
        Path labels = temporaryDirectory.resolve("mandatory-labels.pdf");
        Path picking = temporaryDirectory.resolve("mandatory-picking.pdf");
        OzonPrintBundleService service = new OzonPrintBundleService(
                new OzonPostingRepository(),
                new OzonExemplarJobRepository(),
                (selectedShop, postingNumber) ->
                        new OzonPreparationResult(postingNumber, "NOT_REQUIRED", 0, false, false, ""),
                (selectedShop, postingNumber, target) -> {
                    throw new AssertionError("mandatory KIZ must block before label download");
                });

        IOException failure = assertThrows(IOException.class, () ->
                service.export(shop, "POST-1", labels.toFile(), picking.toFile()));

        assertTrue(failure.getMessage().contains("requiring KIZ"));
        assertFalse(Files.exists(labels));
        assertFalse(Files.exists(picking));
    }

    @Test
    void printAllMergesEveryPostingIntoOneLabelFileAndOnePickingFile() throws Exception {
        seedUnmarkedPosting("POST-1", "SKU-1", "offer-black-64");
        seedUnmarkedPosting("POST-2", "SKU-2", "offer-blue-68");
        OzonProductKizPolicyRepository policies = new OzonProductKizPolicyRepository();
        policies.setRequired(1, "SKU-1", false);
        policies.setRequired(1, "SKU-2", false);
        Path labels = temporaryDirectory.resolve("all-labels.pdf");
        Path picking = temporaryDirectory.resolve("all-picking.pdf");

        OzonPrintBundleService.BatchExportResult result = service(new AtomicBoolean()).exportAll(
                shop, List.of("POST-1", "POST-2"), labels.toFile(), picking.toFile());

        assertEquals(2, result.postingCount());
        assertEquals(4, result.totalPages());
        try (PDDocument document = Loader.loadPDF(labels.toFile())) {
            assertEquals(4, document.getNumberOfPages());
        }
        try (PDDocument document = Loader.loadPDF(picking.toFile())) {
            assertEquals(1, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("offer-black-64"));
            assertTrue(text.contains("offer-blue-68"));
            assertPickingColumnOrder(text);
            assertPageMillimeters(document, 0, 210, 297);
        }
    }

    @Test
    void printAllOmitsProductLabelOnlyForPostingWithCombinedKizCoverage() throws Exception {
        seedPosting(1);
        seedAcceptedExemplars(List.of(RAW_KIZ));
        seedUnmarkedPosting("POST-2", "SKU-2", "offer-blue-68");
        new OzonProductKizPolicyRepository().setRequired(1, "SKU-2", false);
        Path labels = temporaryDirectory.resolve("mixed-labels.pdf");
        Path picking = temporaryDirectory.resolve("mixed-picking.pdf");
        var progress = new java.util.ArrayList<Integer>();

        var result = service(new AtomicBoolean()).exportAll(
                shop, List.of("POST-1", "POST-2"), labels.toFile(), picking.toFile(), progress::add);

        assertEquals(2, result.postingCount());
        assertEquals(1, result.kizPages());
        assertEquals(4, result.totalPages());
        assertEquals(List.of(1, 2), progress);
        try (PDDocument document = Loader.loadPDF(labels.toFile())) {
            assertEquals(4, document.getNumberOfPages());
            assertTrue(pageText(document, 0).contains("Official Ozon shipping page"));
            assertEquals(PRODUCT_BARCODE, decodeProductBarcode(document, 1, 203));
            assertTrue(pageText(document, 2).contains("Official Ozon shipping page"));
            assertTrue(pageText(document, 3).contains("Official Ozon product label"),
                    "A posting without a combined label must retain its official product label");
        }
        assertEquals("ACCEPTED", scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51"));
    }

    @Test
    void batchPickingListContainsAllFortyOneItemsInTheFiveRequestedColumns() throws Exception {
        for (int index = 1; index <= 41; index++) {
            seedUnmarkedPosting("POST-" + index, "SKU-" + index, "article-black-" + (40 + index));
        }
        List<OzonPostingDto> postings = java.util.stream.IntStream.rangeClosed(1, 41)
                .mapToObj(index -> new OzonPostingRepository().find(1, "POST-" + index))
                .toList();
        Path picking = temporaryDirectory.resolve("forty-one-orders-picking.pdf");

        new OzonPickingListPdfExporter().exportBatch(picking.toFile(), shop, postings);

        try (PDDocument document = Loader.loadPDF(picking.toFile())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(document.getNumberOfPages() > 1);
            assertFalse(text.contains("POST-1"));
            assertFalse(text.contains("POST-41"));
            assertTrue(text.contains("Product SKU-41"));
            assertTrue(text.contains("article-black-81"));
            assertPickingColumnOrder(text);
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                assertPageMillimeters(document, page, 210, 297);
            }
        }
    }

    @Test
    void thousandOrderBatchStreamsAllPagesAndReportsOrderedProgress() throws Exception {
        List<String> numbers = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(index -> "BATCH-" + index).toList();
        List<OzonPostingDto> selected = numbers.stream().map(number -> new OzonPostingDto(
                number, number, number, "awaiting_deliver", "", "", "", "", "", "",
                new OzonRequirements(List.of(), List.of(), List.of()), List.of(), false,
                List.of(new OzonPostingItemDto(0, "BATCH-PRODUCT", "BATCH-SKU", "article-batch",
                        "Product " + number, 1, "RUB", "1")))).toList();
        new OzonPostingRepository().upsertPage(1, selected);
        new OzonProductKizPolicyRepository().setRequired(1, "BATCH-SKU", false);
        java.util.concurrent.atomic.AtomicInteger preparations = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger operations = new java.util.concurrent.atomic.AtomicInteger();
        List<Integer> progress = new java.util.ArrayList<>();
        var preparation = new OzonPrintBundleService.Preparation() {
            @Override public OzonPreparationResult prepare(Shop selectedShop, String number) {
                assertEquals(1, selectedShop.getId());
                preparations.incrementAndGet();
                return new OzonPreparationResult(number, "NOT_REQUIRED", 0, true, false, "");
            }
            @Override public boolean refreshEveryNewPrint() { return true; }
            @Override public OzonPrintBundleService.Preparation forBatch(Shop selectedShop) {
                operations.incrementAndGet();
                return this;
            }
        };
        var exporter = new OzonPrintBundleService(new OzonPostingRepository(), new OzonExemplarJobRepository(),
                preparation, (selectedShop, number, target) -> {
                    Files.copy(officialPdf, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return target;
                });
        Path labels = temporaryDirectory.resolve("thousand-labels.pdf");
        Path picking = temporaryDirectory.resolve("thousand-picking.pdf");
        var result = exporter.exportAll(shop, numbers, labels.toFile(), picking.toFile(), progress::add);
        assertEquals(1000, result.postingCount());
        assertEquals(2000, result.totalPages());
        assertEquals(1000, preparations.get(), "Every new print requires refreshed requirements, even cached exemptions");
        assertEquals(1, operations.get(), "One operation-scoped mapping/credential snapshot");
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 1000).boxed().toList(), progress);
        try (PDDocument document = Loader.loadPDF(labels.toFile())) {
            assertEquals(2000, document.getNumberOfPages());
            assertPageMillimeters(document, 1999, 58, 40);
        }
        try (PDDocument document = Loader.loadPDF(picking.toFile())) {
            assertTrue(document.getNumberOfPages() > 10);
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Product BATCH-999"));
            assertTrue(text.contains("article-batch"));
            assertPickingColumnOrder(text);
        }
    }

    @Test
    void refreshedMandatoryRequirementBlocksStaleCachedNonKizPrint() throws Exception {
        seedUnmarkedPosting("POST-1", "SKU-1", "article");
        new OzonProductKizPolicyRepository().setRequired(1, "SKU-1", false);
        var preparation = new OzonPrintBundleService.Preparation() {
            @Override public boolean refreshEveryNewPrint() { return true; }
            @Override public OzonPreparationResult prepare(Shop selectedShop, String number) {
                OzonPostingDto old = new OzonPostingRepository().find(1, number);
                new OzonPostingRepository().upsertDetail(1, new OzonPostingDto(old.postingNumber(), old.orderId(),
                        old.orderNumber(), old.status(), old.substatus(), old.warehouseId(), old.shipmentAt(),
                        old.inProcessAt(), old.lowerBarcode(), old.upperBarcode(),
                        new OzonRequirements(List.of("POST-1-PRODUCT"), List.of(), List.of()),
                        old.availableActions(), old.shipAvailable(), old.items()));
                return new OzonPreparationResult(number, "NOT_REQUIRED", 0, true, false, "");
            }
        };
        var exporter = new OzonPrintBundleService(new OzonPostingRepository(), new OzonExemplarJobRepository(),
                preparation, (selectedShop, number, target) -> { throw new AssertionError("Fresh mandatory requirement must block download"); });
        Path labels = temporaryDirectory.resolve("blocked-fresh.pdf");
        IOException failure = assertThrows(IOException.class, () -> exporter.exportAll(shop, List.of("POST-1"),
                labels.toFile(), temporaryDirectory.resolve("blocked-fresh-picking.pdf").toFile()));
        assertTrue(failure.getMessage().contains("requiring KIZ"));
        assertFalse(Files.exists(labels));
    }

    @Test
    void everyNewPrintRefreshesEvenAnExistingValidatedReservation() throws Exception {
        seedPosting(1);
        seedValidatedExemplar(RAW_KIZ);
        java.util.concurrent.atomic.AtomicInteger refreshes = new java.util.concurrent.atomic.AtomicInteger();
        var preparation = new OzonPrintBundleService.Preparation() {
            @Override public boolean refreshEveryNewPrint() { return true; }
            @Override public OzonPreparationResult prepare(Shop selectedShop, String number) {
                refreshes.incrementAndGet();
                return new OzonPreparationResult(number, "VALIDATED", 1, true, false, "");
            }
        };
        var exporter = new OzonPrintBundleService(new OzonPostingRepository(), new OzonExemplarJobRepository(),
                preparation, (selectedShop, number, target) -> {
                    Files.copy(officialPdf, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return target;
                });
        exporter.exportAll(shop, List.of("POST-1"), temporaryDirectory.resolve("validated-fresh.pdf").toFile(),
                temporaryDirectory.resolve("validated-fresh-picking.pdf").toFile());
        assertEquals(1, refreshes.get());
        assertEquals("CONSUMED", scalar("SELECT status FROM kiz_codes WHERE id=100"));
        assertEquals("ACCEPTED", scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51"));
    }

    private OzonPrintBundleService service(AtomicBoolean prepared) {
        return new OzonPrintBundleService(
                new OzonPostingRepository(),
                new OzonExemplarJobRepository(),
                (selectedShop, postingNumber) -> {
                    prepared.set(true);
                    throw new AssertionError("accepted jobs must not prepare again");
                },
                (selectedShop, postingNumber, target) -> {
                    Files.copy(officialPdf, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return target;
                });
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = Database.getConnection();
                ResultSet result = connection.createStatement().executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private void seedPosting(int quantity) {
        seedPosting(quantity, false);
    }

    private void seedPosting(int quantity, boolean mandatory) {
        new OzonPostingRepository().upsertDetail(1, new OzonPostingDto(
                "POST-1", "ORDER-1", "100001", "awaiting_deliver", "posting_transferring_to_delivery",
                "warehouse-1", "2026-08-19T10:00:00Z", "2026-08-19T09:00:00Z", "LOWER", "UPPER",
                new OzonRequirements(
                        mandatory ? List.of("1001") : List.of(),
                        mandatory ? List.of() : List.of("1001"),
                        List.of()),
                List.of(), false,
                List.of(new OzonPostingItemDto(
                        0, "1001", "SKU-3583", "offer-black-176", "Men's sports suit", quantity, "RUB", "1990"))));
        String imageUrl = "https://example.invalid/ozon-product.png";
        new OzonCatalogRepository().upsertPage(1, List.of(new OzonProductDto(
                "1001", "offer-black-176", "SKU-3583", "Men's sports suit", imageUrl,
                "seller-article", "deep black", "XL",
                false, "2026-08-19T00:00:00Z", List.of("04645588781154", PRODUCT_BARCODE))), "");
        new ImageCacheRepository().saveImage(
                PrintHistoryService.imageCacheKey(imageUrl), imageUrl, sampleProductPng(), "image/png");
    }

    private void seedUnmarkedPosting(String postingNumber, String sku, String offerId) {
        String productId = postingNumber + "-PRODUCT";
        new OzonPostingRepository().upsertDetail(1, new OzonPostingDto(
                postingNumber, postingNumber + "-ORDER", postingNumber + "-ORDER", "awaiting_deliver", "", "",
                "", "", "", "", new OzonRequirements(List.of(), List.of(), List.of()), List.of(), false,
                List.of(new OzonPostingItemDto(0, productId, sku, offerId, "Product " + sku, 1, "RUB", "1"))));
        String[] variant = offerId.split("-");
        String color = variant.length >= 2 ? variant[variant.length - 2] : "";
        String size = variant.length >= 1 ? variant[variant.length - 1] : "";
        new OzonCatalogRepository().upsertPage(1, List.of(new OzonProductDto(
                productId, offerId, sku, "Product " + sku, "", offerId, color, size,
                false, "", List.of())), postingNumber);
    }

    private static void assertPickingColumnOrder(String text) {
        List<String> headers = List.of(
                I18nService.getInstance().tr("ozon.picking.column.index"),
                I18nService.getInstance().tr("ozon.dashboard.col.image"),
                I18nService.getInstance().tr("ozon.picking.column.name"),
                I18nService.getInstance().tr("ozon.dashboard.item.article"),
                I18nService.getInstance().tr("fbo.column.quantity"));
        int previous = -1;
        for (String header : headers) {
            int position = text.indexOf(header, previous + 1);
            assertTrue(position > previous, "Picking column is missing or out of order: " + header);
            previous = position;
        }
    }

    private void seedAcceptedExemplars(List<String> rawCodes) throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) "
                    + "VALUES(1,'04645588781154','Suit','2026-08-19T00:00:00Z')");
            statement.execute("INSERT INTO kiz_orders(id,shop_id,external_order_id,gtin,quantity,remote_status,"
                    + "local_status,created_at,updated_at) VALUES(1,1,'KIZ-ORDER','04645588781154',"
                    + rawCodes.size() + ",'ready','COMPLETED','2026-08-19T00:00:00Z','2026-08-19T00:00:00Z')");
            statement.execute("INSERT INTO ozon_exemplar_jobs(id,shop_id,posting_number,stage,created_at,updated_at) "
                    + "VALUES(51,1,'POST-1','ACCEPTED','2026-08-19T00:00:00Z','2026-08-19T00:00:00Z')");
        }
        for (int index = 0; index < rawCodes.size(); index++) {
            long kizId = 100 + index;
            try (Connection connection = Database.getConnection();
                    PreparedStatement kiz = connection.prepareStatement("""
                            INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,
                                created_at,updated_at) VALUES(?,?,?,?,?,'04645588781154','CONSUMED',?,?)
                            """);
                    PreparedStatement exemplar = connection.prepareStatement("""
                            INSERT INTO ozon_exemplars(job_id,shop_id,posting_number,item_index,product_id,
                                exemplar_id,exemplar_index,kiz_id,check_status,updated_at)
                            VALUES(51,1,'POST-1',0,'1001',?,?,?,'passed',?)
                            """)) {
                kiz.setLong(1, kizId);
                kiz.setInt(2, 1);
                kiz.setInt(3, 1);
                kiz.setString(4, rawCodes.get(index));
                kiz.setString(5, "KIZ-" + index);
                kiz.setString(6, "2026-08-19T00:00:00Z");
                kiz.setString(7, "2026-08-19T00:00:00Z");
                kiz.executeUpdate();
                exemplar.setString(1, "700" + index);
                exemplar.setInt(2, index);
                exemplar.setLong(3, kizId);
                exemplar.setString(4, "2026-08-19T00:00:00Z");
                exemplar.executeUpdate();
            }
        }
    }

    private void seedValidatedExemplar(String rawCode) throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) "
                    + "VALUES(1,'04645588781154','Suit','2026-08-19T00:00:00Z')");
            statement.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) "
                    + "VALUES(1,1,'04645588781154',1,'COMPLETED','2026-08-19T00:00:00Z','2026-08-19T00:00:00Z')");
            statement.execute("INSERT INTO ozon_exemplar_jobs(id,shop_id,posting_number,stage,created_at,updated_at) "
                    + "VALUES(51,1,'POST-1','VALIDATED','2026-08-19T00:00:00Z','2026-08-19T00:00:00Z')");
        }
        try (Connection connection = Database.getConnection();
                PreparedStatement kiz = connection.prepareStatement("""
                        INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,
                            reservation_token,reserved_at,reservation_recoverable,created_at,updated_at)
                        VALUES(100,1,1,?,?,'04645588781154','RESERVED','ozon:51',?,0,?,?)
                        """);
                PreparedStatement exemplar = connection.prepareStatement("""
                        INSERT INTO ozon_exemplars(job_id,shop_id,posting_number,item_index,product_id,
                            exemplar_id,exemplar_index,kiz_id,updated_at)
                        VALUES(51,1,'POST-1',0,'1001','7001',0,100,?)
                        """)) {
            String now = "2026-08-19T00:00:00Z";
            kiz.setString(1, rawCode);
            kiz.setString(2, "KIZ-0");
            kiz.setString(3, now);
            kiz.setString(4, now);
            kiz.setString(5, now);
            kiz.executeUpdate();
            exemplar.setString(1, now);
            exemplar.executeUpdate();
        }
    }

    private static void writeOfficialTwoPagePdf(Path target) throws IOException {
        try (PdfDocument document = new PdfDocument(new PdfWriter(target.toFile()))) {
            var shipping = document.addNewPage(new PageSize(LABEL_WIDTH, LABEL_HEIGHT));
            new PdfCanvas(shipping).beginText().setFontAndSize(PdfFontFactory.createFont(), 8)
                    .moveText(10, 10).showText("Official Ozon shipping page 1").endText();
            var product = document.addNewPage(new PageSize(LABEL_WIDTH, LABEL_HEIGHT));
            new PdfCanvas(product).beginText().setFontAndSize(PdfFontFactory.createFont(), 8)
                    .moveText(10, 90).showText("Official Ozon product label").endText();
            Barcode128 barcode = new Barcode128(document);
            barcode.setCode(PRODUCT_BARCODE);
            barcode.setSize(8);
            barcode.setBarHeight(25);
            barcode.setX(0.75f);
            PdfCanvas canvas = new PdfCanvas(product).saveState().concatMatrix(1, 0, 0, 1,
                    (LABEL_WIDTH - barcode.getBarcodeSize().getWidth()) / 2, 30);
            barcode.placeBarcode(canvas, ColorConstants.BLACK, ColorConstants.BLACK);
            canvas.restoreState();
        }
    }

    private static void assertPageMillimeters(
            PDDocument document, int pageIndex, double expectedWidth, double expectedHeight) {
        double width = document.getPage(pageIndex).getMediaBox().getWidth() * 25.4 / 72;
        double height = document.getPage(pageIndex).getMediaBox().getHeight() * 25.4 / 72;
        assertEquals(expectedWidth, width, 0.2);
        assertEquals(expectedHeight, height, 0.2);
    }

    private static Result decodeRenderedDataMatrixResult(
            PDDocument document, int pageIndex, float dpi) throws Exception {
        BufferedImage page = new PDFRenderer(document).renderImageWithDPI(pageIndex, dpi, ImageType.GRAY);
        float scale = dpi / 72f;
        int margin = Math.round(3 * scale);
        int x = Math.max(0, Math.round((float) (2.5 * 72 / 25.4) * scale) - margin);
        int y = Math.max(0, Math.round((float) (5 * 72 / 25.4) * scale) - margin);
        int side = Math.min(
                Math.round(50 * scale) + margin * 2,
                Math.min(page.getWidth() - x, page.getHeight() - y));
        BufferedImage barcode = page.getSubimage(x, y, side, side);
        BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(barcode)));
        return new DataMatrixReader().decode(
                bitmap, Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE));
    }

    private static String decodeProductBarcode(PDDocument document, int pageIndex, float dpi) throws Exception {
        BufferedImage page = new PDFRenderer(document).renderImageWithDPI(pageIndex, dpi, ImageType.GRAY);
        int y = Math.round((float) (27.5 * dpi / 25.4));
        BufferedImage barcode = page.getSubimage(0, y, page.getWidth(), page.getHeight() - y);
        return new Code128Reader().decode(new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(barcode))),
                Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
    }

    private static int imageCount(PDDocument document, int pageIndex) throws IOException {
        int count = 0;
        for (COSName name : document.getPage(pageIndex).getResources().getXObjectNames()) {
            if (document.getPage(pageIndex).getResources().getXObject(name) instanceof PDImageXObject) count++;
        }
        return count;
    }

    private static List<String> pageOperators(PDDocument document, int pageIndex) throws IOException {
        return new PDFStreamParser(document.getPage(pageIndex)).parse().stream()
                .filter(Operator.class::isInstance)
                .map(Operator.class::cast)
                .map(Operator::getName)
                .toList();
    }

    private static String pageText(PDDocument document, int pageIndex) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        return stripper.getText(document);
    }

    private static byte[] sampleProductPng() {
        try {
            BufferedImage image = new BufferedImage(80, 100, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 80, 100);
            graphics.setColor(new Color(25, 80, 160));
            graphics.fillRect(12, 10, 56, 80);
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
