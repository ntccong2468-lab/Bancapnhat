package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
            assertTrue(text.contains("POST-1"), "The picking row must identify its Ozon posting");
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

    @Test void requestedCarrierLabelsAreNotExcludedByMissingKizOrUnsupportedLocalReadiness()throws Exception {
        var reasons=java.util.Map.of("POST-1","MAPPING_MISSING","POST-2","KIZ_STOCK_SHORTAGE","POST-3","REQUIREMENT_UNSUPPORTED");
        for(int i=1;i<=3;i++)seedUnmarkedPosting("POST-"+i,"SKU-"+i,"offer-"+i);
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),
                (owner,number)->{throw new AssertionError("Carrier-only export must not reserve, validate or consume KIZ");},
                (owner,number,target)->{Files.copy(officialPdf,target.toPath(),StandardCopyOption.REPLACE_EXISTING);return target;});
        var labels=temporaryDirectory.resolve("carrier-only.pdf");var picking=temporaryDirectory.resolve("carrier-only-picking.pdf");
        var result=exporter.exportRequested(shop,List.of("POST-1","POST-2","POST-3"),labels.toFile(),picking.toFile(),null,reasons);
        assertEquals(List.of("POST-1","POST-2","POST-3"),result.successful());assertEquals(reasons,result.carrierOnly());assertTrue(result.failures().isEmpty());assertEquals(0,result.kizPages());
        try(var pdf=Loader.loadPDF(labels.toFile())){assertEquals(result.totalPages(),pdf.getNumberOfPages());assertEquals(6,pdf.getNumberOfPages());}
        assertEquals("3",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));assertEquals("0",scalar("SELECT count(*) FROM ozon_exemplar_jobs"));
        try(var pdf=Loader.loadPDF(picking.toFile())){String text=new PDFTextStripper().getText(pdf);for(int i=1;i<=3;i++)assertTrue(text.contains("offer-"+i));}
    }
    @Test void stalePreflightCannotOmitAnAlreadyReservedCombinedLabel()throws Exception {
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),
                (owner,number)->{throw new AssertionError("Do not prepare KIZ in a carrier-only export");},
                (owner,number,target)->{Files.copy(officialPdf,target.toPath(),StandardCopyOption.REPLACE_EXISTING);return target;});
        var result=exporter.exportRequested(shop,List.of("POST-1"),temporaryDirectory.resolve("reserved-carrier.pdf").toFile(),temporaryDirectory.resolve("reserved-pick.pdf").toFile(),null,java.util.Map.of("POST-1","KIZ_NOT_INCLUDED"));
        assertEquals(List.of("POST-1"),result.successful());assertEquals(1,result.kizPages());
        assertTrue(result.carrierOnly().isEmpty());
        assertEquals("CONSUMED",scalar("SELECT status FROM kiz_codes WHERE id=100"));
        assertEquals("ACCEPTED",scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51"));
    }
    @Test void rateLimitedPreparationDoesNotFallBackToMoreLabelRequests()throws Exception {
        for(int i=1;i<=2;i++)seedUnmarkedPosting("POST-"+i,"SKU-"+i,"offer-"+i);
        var operation=new OzonPrintBundleService.Preparation(){
            public boolean refreshEveryNewPrint(){return true;}
            public OzonPreparationResult prepare(Shop owner,String number)throws IOException{throw new OzonApiException("rate_limited",429,true,false,null);}
        };
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),operation,
                (owner,number,target)->{throw new AssertionError("Respect a server cooldown before requesting more labels");});
        var result=exporter.exportRequested(shop,List.of("POST-1","POST-2"),temporaryDirectory.resolve("limited-labels.pdf").toFile(),temporaryDirectory.resolve("limited-pick.pdf").toFile(),null,java.util.Map.of());
        assertTrue(result.successful().isEmpty());assertEquals(java.util.Set.of("POST-1","POST-2"),result.waiting());assertEquals(result.waiting(),result.failures().keySet());assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }

    @Test void wholeTaskIsDownloadedOnceAndExportsOnlyShippingAndVerifiedCombinedLabels()throws Exception {
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);seedUnmarkedPosting("POST-2","SKU-2","offer-2");
        var batchFile=temporaryDirectory.resolve("whole-source.pdf");
        try(var original=new PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(officialPdf.toFile()));
            var batch=new PdfDocument(new PdfWriter(batchFile.toFile()))){
            // A shuffled native task must be sorted by WB rules, without per-order downloads.
            original.copyPagesTo(List.of(2,1,1,2),batch);
            new PdfCanvas(batch.getPage(2)).beginText().setFontAndSize(PdfFontFactory.createFont(),9).moveText(10,40).showText("POST-2").endText();
            new PdfCanvas(batch.getPage(3)).beginText().setFontAndSize(PdfFontFactory.createFont(),9).moveText(10,40).showText("POST-1").endText();
        }
        var source=new PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(batchFile.toFile()));
        var batches=new java.util.concurrent.atomic.AtomicInteger();
        var downloader=new OzonPrintBundleService.OfficialLabelDownloader(){
            public java.io.File download(Shop owner,String number,java.io.File target){throw new AssertionError("No per-posting label downloads");}
            public boolean supportsWholeBatches(){return true;}
            public OzonPrintBundleService.WholeCarrierBatch wholeBatch(Shop owner,String number){batches.incrementAndGet();return new OzonPrintBundleService.WholeCarrierBatch(source,batchFile.toFile(),List.of(1,2,3,4),List.of("POST-1","POST-2"),java.util.Set.of());}
            public void close(){source.close();}
        };
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),
                (owner,number)->{throw new AssertionError("Do not repeat existing KIZ preparation or prepare blocked KIZ");},downloader);
        new com.vncode.app.features.supply.OrderSortPreferenceService().save(new com.vncode.app.features.supply.OrderSortOptions(false,false,false,false));
        var labels=temporaryDirectory.resolve("whole-with-kiz.pdf");
        var result=exporter.exportRequested(shop,List.of("POST-2","POST-1"),labels.toFile(),temporaryDirectory.resolve("whole-with-kiz-pick.pdf").toFile(),null,java.util.Map.of("POST-2","MAPPING_MISSING"));
        assertEquals(List.of("POST-1","POST-2"),result.successful());assertEquals(1,result.kizPages());assertEquals(3,result.totalPages());assertEquals(java.util.Set.of("POST-2"),result.carrierOnly().keySet());
        assertEquals(1,batches.get(),"Keep the grouped download flow even without KIZ for one posting");
        try(var output=Loader.loadPDF(labels.toFile())){
            assertEquals(3,output.getNumberOfPages());assertPageMillimeters(output,1,58,40);
            assertFalse(new PDFTextStripper().getText(output).contains("Official Ozon product label"));
            assertTrue(pageText(output,0).contains("2345"));
            assertTrue(pageText(output,2).contains("2345"));
            assertTrue(pageText(output,0).contains("POST-1"));assertTrue(pageText(output,2).contains("POST-2"));
            assertTrue(pageText(output,0).contains("shipping"));assertTrue(pageText(output,2).contains("shipping"));
            assertEquals(PRODUCT_BARCODE,decodeProductBarcode(output,1,203));
        }
        try(var official=new PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(batchFile.toFile()));
            var output=new PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(labels.toFile()))){
            // Copy the original vector label: large white last-four digits, black background,
            // barcode geometry and original 58x40 mm media all survive without reconstruction.
            assertArrayEquals(official.getPage(3).getContentBytes(),output.getPage(1).getContentBytes());
            assertArrayEquals(official.getPage(2).getContentBytes(),output.getPage(3).getContentBytes());
            assertEquals(official.getPage(2).getPageSize().toString(),output.getPage(1).getPageSize().toString());
        }
        assertEquals("CONSUMED",scalar("SELECT status FROM kiz_codes WHERE id=100"));assertEquals("2",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));assertTrue(source.isClosed());
    }

    @Test void opaqueWholeTaskKeepsCarrierPagesButNeverPairsOrConsumesReservedKiz()throws Exception {
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);seedUnmarkedPosting("POST-2","SKU-2","offer-2");
        var file=temporaryDirectory.resolve("opaque-task.pdf");
        try(var original=new PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(officialPdf.toFile()));var pdf=new PdfDocument(new PdfWriter(file.toFile()))){original.copyPagesTo(List.of(1,1),pdf);}
        var source=new PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(file.toFile()));
        var downloader=new OzonPrintBundleService.OfficialLabelDownloader(){
            public File download(Shop owner,String number,File target){throw new AssertionError("No individual label requests");}
            public boolean supportsWholeBatches(){return true;}
            public OzonPrintBundleService.WholeCarrierBatch wholeBatch(Shop owner,String number){return new OzonPrintBundleService.WholeCarrierBatch(source,file.toFile(),List.of(1,2),List.of("POST-1","POST-2"),java.util.Set.of());}
            public void close(){source.close();}
        };
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),
                (owner,number)->{throw new AssertionError("Existing KIZ reservation");},downloader);
        var result=exporter.exportRequested(shop,List.of("POST-1","POST-2"),temporaryDirectory.resolve("opaque-labels.pdf").toFile(),temporaryDirectory.resolve("opaque-pick.pdf").toFile(),null,Map.of("POST-2","KIZ_STOCK_SHORTAGE"));
        assertEquals(2,result.successful().size());assertEquals(2,result.totalPages());assertEquals(0,result.kizPages());
        assertEquals(Map.of("POST-1","OZON_LABEL_BATCH_IDENTITY_UNVERIFIED","POST-2","OZON_LABEL_BATCH_IDENTITY_UNVERIFIED"),result.carrierOnly());
        assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=100"));
    }

    @Test void oneUnidentifiedCarrierDoesNotRemoveCombinedLabelsFromIdentifiedPostings()throws Exception {
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);seedUnmarkedPosting("POST-2","SKU-2","offer-2");
        var file=temporaryDirectory.resolve("partly-opaque-source.pdf");
        try(var original=new PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(officialPdf.toFile()));
            var pdf=new PdfDocument(new PdfWriter(file.toFile()))){
            original.copyPagesTo(List.of(1,1),pdf);
            new PdfCanvas(pdf.getPage(1)).beginText().setFontAndSize(PdfFontFactory.createFont(),9)
                    .moveText(10,40).showText("POST-1").endText();
        }
        var source=new PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(file.toFile()));
        var downloader=new OzonPrintBundleService.OfficialLabelDownloader(){
            public File download(Shop owner,String number,File target){throw new AssertionError("Keep batch downloads");}
            public boolean supportsWholeBatches(){return true;}
            public OzonPrintBundleService.WholeCarrierBatch wholeBatch(Shop owner,String number){
                return new OzonPrintBundleService.WholeCarrierBatch(source,file.toFile(),List.of(1,2),List.of("POST-1","POST-2"),java.util.Set.of());
            }
            public void close(){source.close();}
        };
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),
                (owner,number)->{throw new AssertionError("Existing binding");},downloader);
        var result=exporter.exportRequested(shop,List.of("POST-1","POST-2"),temporaryDirectory.resolve("partly-opaque.pdf").toFile(),
                temporaryDirectory.resolve("partly-opaque-pick.pdf").toFile(),null,Map.of("POST-2","KIZ_STOCK_SHORTAGE"));
        assertEquals(1,result.kizPages());assertEquals(3,result.totalPages());assertEquals(2,result.successful().size());
        assertEquals(Map.of("POST-2","OZON_LABEL_BATCH_IDENTITY_UNVERIFIED"),result.carrierOnly());
        try(var pdf=Loader.loadPDF(result.labelFile())){
            assertTrue(pageText(pdf,0).contains("POST-1"));
            assertEquals(KizService.scannerSafeCode(RAW_KIZ),KizService.scannerSafeCode(decodeRenderedDataMatrixResult(pdf,1,300).getText()));
        }
    }

    @Test void hundredOrdersEachKeepTheirCombinedLabelAndReprintWithoutAnotherReservation()throws Exception {
        seedPosting(1);
        var template=new OzonPostingRepository().find(1,"POST-1");
        var numbers=java.util.stream.IntStream.rangeClosed(1,100).mapToObj(i->"POST-"+i).toList();
        new OzonPostingRepository().upsertPage(1,numbers.stream().map(number->new OzonPostingDto(number,number+"-ORDER",number+"-ORDER",
                "awaiting_deliver","","","","","","",template.requirements(),List.of(),false,template.items())).toList());
        com.vncode.app.testsupport.CategoryStockFixture.add(shop,"1001",temporaryDirectory,100);
        var jobs=new OzonExemplarJobRepository();var expected=new java.util.LinkedHashMap<String,String>();
        for(String number:numbers){
            var plan=new OzonRequirementGuard.PreparationPlan(number,List.of(new OzonRequirementGuard.RequiredItem(0,"1001","SKU-3583","",1,true)));
            var reservation=jobs.stageLocalForPrint(1,plan,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY,()->{});
            expected.put(number,jobs.bindings(reservation.job().id()).getFirst().rawCode());
        }
        var input=temporaryDirectory.resolve("hundred-source.pdf");
        try(var original=new PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(officialPdf.toFile()));
            var pdf=new PdfDocument(new PdfWriter(input.toFile()))){
            for(String number:numbers.reversed()){
                original.copyPagesTo(1,1,pdf);
                new PdfCanvas(pdf.getLastPage()).beginText().setFontAndSize(PdfFontFactory.createFont(),9)
                        .moveText(10,40).showText(number).endText();
            }
        }
        var downloads=new java.util.concurrent.atomic.AtomicInteger();
        var downloader=new OzonPrintBundleService.OfficialLabelDownloader(){
            public File download(Shop owner,String number,File target){throw new AssertionError("No per-order download");}
            public OzonPrintBundleService.OfficialLabelDownloader forBatch(Shop owner,List<String> selected){
                try{
                    var source=new PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(input.toFile()));
                    return new OzonPrintBundleService.OfficialLabelDownloader(){
                        public File download(Shop ignored,String number,File target){throw new AssertionError("No per-order download");}
                        public boolean supportsWholeBatches(){return true;}
                        public OzonPrintBundleService.WholeCarrierBatch wholeBatch(Shop ignored,String number){
                            downloads.incrementAndGet();return new OzonPrintBundleService.WholeCarrierBatch(source,input.toFile(),
                                    java.util.stream.IntStream.rangeClosed(1,100).boxed().toList(),numbers,java.util.Set.of());
                        }
                        public void close(){source.close();}
                    };
                }catch(IOException failed){throw new java.io.UncheckedIOException(failed);}
            }
        };
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),jobs,
                (owner,number)->{throw new AssertionError("Do not replace already held/printed KIZ");},downloader);
        new com.vncode.app.features.supply.OrderSortPreferenceService().save(new com.vncode.app.features.supply.OrderSortOptions(false,false,false,false));
        long started=System.nanoTime();
        for(int run=0;run<2;run++){
            var result=exporter.exportRequested(shop,numbers.reversed(),temporaryDirectory.resolve("hundred-"+run+".pdf").toFile(),
                    temporaryDirectory.resolve("hundred-pick-"+run+".pdf").toFile(),null,Map.of());
            assertEquals(numbers,result.successful());assertEquals(100,result.kizPages());assertEquals(200,result.totalPages());
            assertTrue(result.failures().isEmpty());assertTrue(result.carrierOnly().isEmpty());
            try(var pdf=Loader.loadPDF(result.labelFile())){
                for(int n=0;n<100;n++){
                    assertTrue(pageText(pdf,n*2).contains(numbers.get(n)));
                    final int labelPage=n*2+1;
                    for(int dpi:new int[]{203,300})assertEquals(KizService.scannerSafeCode(expected.get(numbers.get(n))),
                            KizService.scannerSafeCode(assertDoesNotThrow(()->decodeRenderedDataMatrixResult(pdf,labelPage,dpi),
                                    "Scan synthetic combined page "+(labelPage+1)+" at "+dpi+" dpi").getText()));
                }
            }
        }
        assertEquals(2,downloads.get());assertEquals("100",scalar("SELECT count(*) FROM kiz_codes WHERE status='CONSUMED'"));
        assertEquals("100",scalar("SELECT count(*) FROM kiz_codes"));
        System.out.println("OZON_PAIRED_PDF_FIXTURE orders=100 pages_per_export=200 exports=2 batch_downloads=2 individual_downloads=0 elapsed_ms="+(System.nanoTime()-started)/1_000_000);
    }

    @Test void rejectedRemoteKizStillPrintsItsExactReservedCombinedLabelAndReportsApiRejection()throws Exception {
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);
        new OzonExemplarJobRepository().logAction(1,"accept_kiz","POST-1","rejected","OZON_ACCEPT_KIZ_REJECTED",null);
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),
                (owner,number)->{throw new AssertionError("Keep the exact reservation; do not reserve another code");},
                (owner,number,target)->{Files.copy(officialPdf,target.toPath(),StandardCopyOption.REPLACE_EXISTING);return target;});
        var result=exporter.exportRequested(shop,List.of("POST-1"),temporaryDirectory.resolve("rejected-carrier.pdf").toFile(),temporaryDirectory.resolve("rejected-pick.pdf").toFile(),null,Map.of());
        assertEquals(List.of("POST-1"),result.successful());assertEquals(1,result.kizPages());
        assertTrue(result.carrierOnly().isEmpty());
        assertEquals(Map.of("POST-1","OZON_ACCEPT_KIZ_REJECTED"),result.remoteKizWarnings());
        assertEquals("CONSUMED",scalar("SELECT status FROM kiz_codes WHERE id=100"));
        assertEquals("printed",scalar("SELECT check_status FROM ozon_exemplars WHERE job_id=51"));
        assertEquals("rejected",new OzonExemplarJobRepository().latestActionStatus(1,"accept_kiz","POST-1"));
        try(var pdf=Loader.loadPDF(result.labelFile())){
            assertEquals(KizService.scannerSafeCode(RAW_KIZ),KizService.scannerSafeCode(decodeRenderedDataMatrixResult(pdf,1,300).getText()));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void syncedRejectedKizKeepsCombinedLabelInSingleAndBatchExport(boolean wholeBatch)throws Exception {
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);
        var repository=new com.vncode.app.features.kiz.status.Kn3Repository();
        repository.observe(shop,"POST-1",List.of(new com.vncode.app.features.kiz.status.Kn3Mark(
                "POST-1","1001","7001",RAW_KIZ,com.vncode.app.features.kiz.status.Kn3Mark.State.REJECTED,
                "MANDATORY_MARK_IN_INCORRECT_STATUS")));
        assertEquals("CONSUMED",scalar("SELECT status FROM kiz_codes WHERE id=100"));
        assertThrows(IllegalStateException.class,()->new OzonExemplarJobRepository().requireCurrentBindingsBeforeSend(shop,"POST-1",51),
                "Local rejected-label permission must not authorize resubmission to Ozon");
        var batchFile=temporaryDirectory.resolve("readback-source.pdf");
        try(var original=new PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(officialPdf.toFile()));
            var batch=new PdfDocument(new PdfWriter(batchFile.toFile()))){
            original.copyPagesTo(1,2,batch);
            new PdfCanvas(batch.getPage(1)).beginText().setFontAndSize(PdfFontFactory.createFont(),9)
                    .moveText(10,40).showText("POST-1").endText();
        }
        var downloads=new java.util.concurrent.atomic.AtomicInteger();
        var source=new PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(batchFile.toFile()));
        var downloader=new OzonPrintBundleService.OfficialLabelDownloader(){
            public File download(Shop owner,String number,File target)throws IOException{
                assertFalse(wholeBatch,"Whole task must not download labels per order");downloads.incrementAndGet();
                Files.copy(batchFile,target.toPath(),StandardCopyOption.REPLACE_EXISTING);return target;
            }
            public boolean supportsWholeBatches(){return wholeBatch;}
            public OzonPrintBundleService.WholeCarrierBatch wholeBatch(Shop owner,String number){
                downloads.incrementAndGet();return new OzonPrintBundleService.WholeCarrierBatch(source,batchFile.toFile(),List.of(1,2),List.of("POST-1"),java.util.Set.of());
            }
            public void close(){source.close();}
        };
        var operation=new OzonPrintBundleService.Preparation(){
            public boolean refreshEveryNewPrint(){return true;}
            public OzonPreparationResult prepare(Shop owner,String number){
                var plan=new OzonRequirementGuard.PreparationPlan(number,List.of(
                        new OzonRequirementGuard.RequiredItem(0,"1001","SKU-3583","04645588781154",1,true)));
                var reservation=new OzonExemplarJobRepository().stageLocalForPrint(owner.getId(),plan,()->{});
                return new OzonPreparationResult(number,reservation.job().stage().name(),1,true,false,"");
            }
        };
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),operation,downloader);
        var labels=temporaryDirectory.resolve("readback-labels.pdf");
        var result=exporter.exportRequested(shop,List.of("POST-1"),labels.toFile(),temporaryDirectory.resolve("readback-pick.pdf").toFile(),null,
                Map.of("POST-1","KIZ_NOT_INCLUDED"));
        assertEquals(1,result.kizPages());assertEquals(2,result.totalPages());assertEquals(1,downloads.get());
        assertTrue(result.carrierOnly().isEmpty());assertTrue(result.failures().isEmpty());
        assertEquals(Map.of("POST-1","OZON_ACCEPT_KIZ_REJECTED"),result.remoteKizWarnings());
        try(var pdf=Loader.loadPDF(labels.toFile())){
            assertEquals(KizService.scannerSafeCode(RAW_KIZ),KizService.scannerSafeCode(decodeRenderedDataMatrixResult(pdf,1,300).getText()));
        }
        assertEquals(com.vncode.app.features.kiz.status.Kn3Mark.State.REJECTED,repository.read(shop,"POST-1").getFirst().state());
        assertEquals("REJECTED",scalar("SELECT legal_status FROM kiz_codes WHERE id=100"));
        assertEquals("1",scalar("SELECT count(*) FROM kiz_codes"));
    }

    @Test void thousandWholeTaskLabelsSortAcrossBatchesWithoutIndividualDownloads()throws Exception {
        var numbers=java.util.stream.IntStream.range(0,1000).mapToObj(i->"SORT-"+i).toList();
        var selected=numbers.stream().map(number->new OzonPostingDto(number,number,number,"awaiting_deliver","","","","","","",
                new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,
                List.of(new OzonPostingItemDto(0,"P","SKU","article","Product "+number,1,"RUB","1")))).toList();
        new OzonPostingRepository().upsertPage(1,selected);new OzonProductKizPolicyRepository().setRequired(1,"SKU",false);
        var file=temporaryDirectory.resolve("whole-1000-source.pdf");
        try(var pdf=new PdfDocument(new PdfWriter(file.toFile()))){
            var font=PdfFontFactory.createFont();
            for(int i=999;i>=0;i--)new PdfCanvas(pdf.addNewPage()).beginText().setFontAndSize(font,12).moveText(20,20).showText(numbers.get(i)).endText();
        }
        var source=new PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(file.toFile()));
        var batches=new java.util.concurrent.atomic.AtomicInteger();
        var downloader=new OzonPrintBundleService.OfficialLabelDownloader(){
            public File download(Shop owner,String number,File target){throw new AssertionError("No individual label requests for 1000 orders");}
            public boolean supportsWholeBatches(){return true;}
            public OzonPrintBundleService.WholeCarrierBatch wholeBatch(Shop owner,String number){
                batches.incrementAndGet();int start=Integer.parseInt(number.substring(5))/100*100;
                return new OzonPrintBundleService.WholeCarrierBatch(source,file.toFile(),
                        java.util.stream.IntStream.range(start,start+100).map(i->1000-i).boxed().toList(),numbers.subList(start,start+100),java.util.Set.of());
            }
            public void close(){source.close();}
        };
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),
                (owner,number)->new OzonPreparationResult(number,"NOT_REQUIRED",0,true,false,""),downloader);
        var labels=temporaryDirectory.resolve("whole-1000.pdf");long started=System.nanoTime();
        var result=exporter.exportRequested(shop,numbers.reversed(),labels.toFile(),temporaryDirectory.resolve("whole-1000-pick.pdf").toFile(),null,Map.of());
        assertEquals(numbers,result.successful());assertEquals(1000,result.totalPages());assertEquals(10,batches.get());assertTrue(result.failures().isEmpty());
        try(var pdf=new PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(labels.toFile()))){
            for(int i=0;i<1000;i++)assertEquals(numbers.get(i),com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(pdf.getPage(i+1)).strip());
        }
        System.out.println("OZON_SORTED_PDF_FIXTURE orders=1000 tasks=10 individual_downloads=0 elapsed_ms="+(System.nanoTime()-started)/1_000_000);
    }

    @Test void rejectedSellerCategoryBindingPrintsWithoutGtinMappingAndCannotBeAllocatedAgain()throws Exception {
        seedPosting(1);
        com.vncode.app.testsupport.CategoryStockFixture.add(shop,"1001",temporaryDirectory,1);
        var posting=new OzonPostingRepository().find(1,"POST-1");
        var plan=OzonRequirementGuard.plan(posting,Map.of(),java.util.Set.of(),java.util.Set.of("SKU-3583"));
        var jobs=new OzonExemplarJobRepository();
        var reservation=jobs.stageLocalForPrint(1,plan,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY,()->{});
        try(var connection=Database.getConnection();var update=connection.prepareStatement("UPDATE ozon_exemplars SET exemplar_id='7001' WHERE job_id=?")){
            update.setLong(1,reservation.job().id());update.executeUpdate();
        }
        var binding=jobs.bindings(reservation.job().id()).getFirst();
        var rejected=new com.vncode.app.features.kiz.status.Kn3Mark("POST-1","1001","7001",binding.rawCode(),
                com.vncode.app.features.kiz.status.Kn3Mark.State.REJECTED,"MANDATORY_MARK_IN_INCORRECT_STATUS");
        new com.vncode.app.features.kiz.status.Kn3Repository().observe(shop,"POST-1",List.of(rejected));
        assertEquals(java.util.Set.of("SKU-3583"),OzonCategoryStock.reservedCategorySkus(1,posting,reservation.job()));
        try(var connection=Database.getConnection();var update=connection.prepareStatement("UPDATE kiz_import_batches SET owner=? WHERE shop_id=1")){
            update.setString(1,"another-account");update.executeUpdate();
            assertThrows(IllegalStateException.class,()->jobs.stageLocalForPrint(1,plan,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY,()->{}));
            update.setString(1,com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop));update.executeUpdate();
        }
        var operation=new OzonPrintBundleService.Preparation(){
            public boolean refreshEveryNewPrint(){return true;}
            public OzonPreparationResult prepare(Shop owner,String number){
                var ready=jobs.stageLocalForPrint(1,plan,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY,()->{});
                return new OzonPreparationResult(number,ready.job().stage().name(),1,true,false,"");
            }
        };
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),jobs,operation,
                (owner,number,target)->{Files.copy(officialPdf,target.toPath(),StandardCopyOption.REPLACE_EXISTING);return target;});
        var result=exporter.exportRequested(shop,List.of("POST-1"),temporaryDirectory.resolve("category-rejected.pdf").toFile(),
                temporaryDirectory.resolve("category-rejected-pick.pdf").toFile(),null,Map.of());
        assertEquals(1,result.kizPages());assertTrue(result.carrierOnly().isEmpty());
        try(var pdf=Loader.loadPDF(result.labelFile())){
            assertEquals(KizService.scannerSafeCode(binding.rawCode()),KizService.scannerSafeCode(decodeRenderedDataMatrixResult(pdf,1,300).getText()));
        }
        assertTrue(new OzonProductGtinMappingRepository().findResolvedBySku(1).isEmpty());
        assertTrue(OzonCategoryStock.coverage(1,List.of(posting),java.util.Set.of()).isEmpty(),"Rejected codes never return to the available pool");
        assertEquals("1",scalar("SELECT count(*) FROM kiz_codes WHERE legal_status='REJECTED' AND status='CONSUMED'"));
    }

    @Test void rejectionOfAnotherPostingDoesNotAuthorizePrintingThisConsumedBinding()throws Exception {
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);
        new com.vncode.app.features.kiz.status.Kn3Repository().observe(shop,"OTHER",List.of(
                new com.vncode.app.features.kiz.status.Kn3Mark("OTHER","1001","7001",RAW_KIZ,
                        com.vncode.app.features.kiz.status.Kn3Mark.State.REJECTED,"REJECTED")));
        var plan=new OzonRequirementGuard.PreparationPlan("POST-1",List.of(
                new OzonRequirementGuard.RequiredItem(0,"1001","SKU-3583","04645588781154",1,true)));
        assertThrows(IllegalStateException.class,()->new OzonExemplarJobRepository().stageLocalForPrint(1,plan,()->{}));
        assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }

    @Test void newlyDetectedKizPreparationFailureStillExportsTheOfficialCarrierLabel()throws Exception {
        seedUnmarkedPosting("POST-1","SKU-1","offer-1");
        var operation=new OzonPrintBundleService.Preparation(){
            public boolean refreshEveryNewPrint(){return true;}
            public OzonPreparationResult prepare(Shop owner,String number)throws IOException{throw new IOException("mapping_missing");}
        };
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),operation,
                (owner,number,target)->{Files.copy(officialPdf,target.toPath(),StandardCopyOption.REPLACE_EXISTING);return target;});
        var result=exporter.exportRequested(shop,List.of("POST-1"),temporaryDirectory.resolve("fresh-carrier.pdf").toFile(),temporaryDirectory.resolve("fresh-pick.pdf").toFile(),null,java.util.Map.of());
        assertEquals(List.of("POST-1"),result.successful());assertTrue(result.carrierOnly().containsKey("POST-1"));assertEquals(0,result.kizPages());assertTrue(result.failures().isEmpty());
    }

    @Test void availableBatchPublishesOnlyCompletePostingsAndReportsTheFailedOrder() throws Exception {
        for(int i=1;i<=3;i++) {seedUnmarkedPosting("POST-"+i,"SKU-"+i,"offer-"+i);new OzonProductKizPolicyRepository().setRequired(1,"SKU-"+i,false);}
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),
                (owner,number)->{throw new AssertionError("Exempt order must not reserve KIZ");},
                (owner,number,target)->{if(number.equals("POST-2"))throw new IOException("OZON_LABEL_BATCH_IDENTITY_UNVERIFIED");Files.copy(officialPdf,target.toPath(),StandardCopyOption.REPLACE_EXISTING);return target;});
        Path labels=temporaryDirectory.resolve("partial.pdf"),picking=temporaryDirectory.resolve("partial-picking.pdf");
        var progress=new java.util.ArrayList<Integer>();
        var result=exporter.exportAvailable(shop,List.of("POST-1","POST-2","POST-3"),labels.toFile(),picking.toFile(),progress::add);
        assertEquals(List.of("POST-1","POST-3"),result.successful());assertEquals(java.util.Set.of("POST-2"),result.failures().keySet());
        assertEquals(List.of(1,2,3),progress);assertEquals(4,result.totalPages());
        try(PDDocument doc=Loader.loadPDF(labels.toFile())){assertEquals(4,doc.getNumberOfPages());}
        try(PDDocument doc=Loader.loadPDF(picking.toFile())){String text=new PDFTextStripper().getText(doc);assertTrue(text.contains("offer-1"));assertFalse(text.contains("offer-2"));assertTrue(text.contains("offer-3"));}
        assertEquals("2",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }

    @Test void failedLabelDoesNotConsumeReservedKizWhenAnotherOrderSucceeds() throws Exception {
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);seedUnmarkedPosting("POST-2","SKU-2","offer-2");
        new OzonProductKizPolicyRepository().setRequired(1,"SKU-2",false);
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),
                (owner,number)->{throw new AssertionError("Existing reservation must not be changed");},
                (owner,number,target)->{if(number.equals("POST-1"))throw new IOException("OZON_LABEL_BATCH_IDENTITY_UNVERIFIED");Files.copy(officialPdf,target.toPath(),StandardCopyOption.REPLACE_EXISTING);return target;});
        var result=exporter.exportAvailable(shop,List.of("POST-1","POST-2"),temporaryDirectory.resolve("safe-part.pdf").toFile(),temporaryDirectory.resolve("safe-pick.pdf").toFile(),null);
        assertEquals(List.of("POST-2"),result.successful());assertEquals(java.util.Set.of("POST-1"),result.failures().keySet());
        assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=100"));assertEquals("VALIDATED",scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51"));
        assertEquals("1",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }

    @Test void rateLimitStopsFurtherRequestsAndPreservesExistingFiles() throws Exception {
        for(int i=1;i<=3;i++) {seedUnmarkedPosting("POST-"+i,"SKU-"+i,"offer-"+i);new OzonProductKizPolicyRepository().setRequired(1,"SKU-"+i,false);}
        var calls=new java.util.ArrayList<String>();
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),
                (owner,number)->{throw new AssertionError("Exempt order must not reserve KIZ");},
                (owner,number,target)->{calls.add(number);throw new OzonApiException("rate_limited",429,true,false,null);});
        Path labels=temporaryDirectory.resolve("existing.pdf"),picking=temporaryDirectory.resolve("existing-pick.pdf");
        Files.writeString(labels,"existing-label");Files.writeString(picking,"existing-picking");
        var result=exporter.exportAvailable(shop,List.of("POST-1","POST-2","POST-3"),labels.toFile(),picking.toFile(),null);
        assertTrue(result.successful().isEmpty());assertEquals(java.util.Set.of("POST-1","POST-2","POST-3"),result.failures().keySet());assertEquals(result.failures().keySet(),result.waiting());assertEquals(List.of("POST-1"),calls);
        assertEquals("existing-label",Files.readString(labels));assertEquals("existing-picking",Files.readString(picking));assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }

    @Test void singleExportWithoutPickingTargetRejectsBeforeTouchingReservedKiz() throws Exception {
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);
        Path labels=temporaryDirectory.resolve("no-picking-kiz.pdf");
        assertThrows(IllegalArgumentException.class,()->service(new AtomicBoolean()).export(shop,"POST-1",labels.toFile(),null));
        assertFalse(Files.exists(labels));
        assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=100"));
        assertEquals("VALIDATED",scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51"));
        assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }

    @Test void unmarkedSingleExportWithoutPickingTargetRejectsBeforeWriting() throws Exception {
        seedUnmarkedPosting("POST-2","SKU-2","offer-2");
        new OzonProductKizPolicyRepository().setRequired(1,"SKU-2",false);
        Path labels=temporaryDirectory.resolve("no-picking-unmarked.pdf");
        assertThrows(IllegalArgumentException.class,()->service(new AtomicBoolean()).export(shop,"POST-2",labels.toFile(),null));
        assertFalse(Files.exists(labels));
        assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }

    @Test void batchAllPickingFailureMustPreservePreviousLabelAndReservedKiz() throws Exception {
        seedPosting(1); seedValidatedExemplar(RAW_KIZ);
        Path labels=temporaryDirectory.resolve("already-batch.pdf"),picking=temporaryDirectory.resolve("blocked-batch.pdf");
        Files.writeString(labels,"original-label");Files.createDirectory(picking);Files.writeString(picking.resolve("keep"),"keep");
        assertThrows(IOException.class,()->service(new AtomicBoolean()).exportAll(shop,List.of("POST-1"),labels.toFile(),picking.toFile()));
        org.junit.jupiter.api.Assertions.assertAll(
                ()->assertArrayEquals("original-label".getBytes(java.nio.charset.StandardCharsets.UTF_8),Files.readAllBytes(labels)),
                ()->assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=100")),
                ()->assertEquals("VALIDATED",scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51")),
                ()->assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts")));
    }

    @Test void singleLabelTargetDirectoryKeepsPickingAndKizReserved() throws Exception {
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);
        Path labels=temporaryDirectory.resolve("blocked-label.pdf"),picking=temporaryDirectory.resolve("existing-picking.pdf");
        Files.createDirectory(labels);Files.writeString(labels.resolve("keep"),"keep");Files.writeString(picking,"original-picking");
        assertThrows(IOException.class,()->service(new AtomicBoolean()).export(shop,"POST-1",labels.toFile(),picking.toFile()));
        assertEquals("keep",Files.readString(labels.resolve("keep")));
        assertEquals("original-picking",Files.readString(picking));
        assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=100"));
        assertEquals("VALIDATED",scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51"));
        assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }

    @Test void singleDatabaseFailureAfterPdfPublishRequiresReconciliation() throws Exception {
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);
        try(var c=Database.getConnection();var statement=c.createStatement()){
            statement.execute("CREATE TRIGGER fail_ozon_single_consume BEFORE UPDATE OF status ON kiz_codes WHEN NEW.status='CONSUMED' BEGIN SELECT RAISE(ABORT,'simulated storage failure'); END");
        }
        Path labels=temporaryDirectory.resolve("published-single.pdf"),picking=temporaryDirectory.resolve("published-single-pick.pdf");
        var error=assertThrows(IOException.class,()->service(new AtomicBoolean()).export(shop,"POST-1",labels.toFile(),picking.toFile()));
        assertEquals("OZON_PDF_PUBLISHED_RECONCILIATION_REQUIRED",error.getMessage());
        assertTrue(Files.isRegularFile(labels));assertTrue(Files.isRegularFile(picking));
        assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=100"));
        assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }

    @Test void singleExportPickingFailureMustPreservePreviousLabelAndReservedKiz() throws Exception {
        seedPosting(1); seedValidatedExemplar(RAW_KIZ);
        Path labels=temporaryDirectory.resolve("already-single.pdf"),picking=temporaryDirectory.resolve("blocked-single.pdf");
        Files.writeString(labels,"original-label"); Files.createDirectory(picking); Files.writeString(picking.resolve("keep"),"keep");
        assertThrows(IOException.class,()->service(new AtomicBoolean()).export(shop,"POST-1",labels.toFile(),picking.toFile()));
        org.junit.jupiter.api.Assertions.assertAll(
                ()->assertArrayEquals("original-label".getBytes(java.nio.charset.StandardCharsets.UTF_8),Files.readAllBytes(labels)),
                ()->assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=100")),
                ()->assertEquals("VALIDATED",scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51")),
                ()->assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts")));
    }

    @Test void singleExportChangedKizDuringRenderMustNotPublishOrConsume() throws Exception {
        seedPosting(1); seedValidatedExemplar(RAW_KIZ);
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),
                (owner,number)->{throw new AssertionError("Existing reservation must not be replaced");},
                (owner,number,target)->{Files.copy(officialPdf,target.toPath(),StandardCopyOption.REPLACE_EXISTING);
                    try(var c=Database.getConnection();var statement=c.prepareStatement("UPDATE kiz_codes SET raw_code=? WHERE id=100")){
                        statement.setString(1,"changed-mark");statement.executeUpdate();
                    }catch(java.sql.SQLException error){throw new IOException(error);}return target;});
        Path labels=temporaryDirectory.resolve("changed-single.pdf"),picking=temporaryDirectory.resolve("changed-single-pick.pdf");
        assertThrows(IOException.class,()->exporter.export(shop,"POST-1",labels.toFile(),picking.toFile()));
        assertFalse(Files.exists(labels));assertFalse(Files.exists(picking));
        assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=100"));
        assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }

    @Test void pickingFilePublishFailureKeepsExistingLabelAndDoesNotConsumeKiz()throws Exception{
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);
        Path labels=temporaryDirectory.resolve("already.pdf"),picking=temporaryDirectory.resolve("blocked.pdf");
        Files.writeString(labels,"original-label");Files.createDirectory(picking);Files.writeString(picking.resolve("keep"),"keep");
        assertThrows(IOException.class,()->service(new AtomicBoolean()).exportAvailable(shop,List.of("POST-1"),labels.toFile(),picking.toFile(),null));
        assertEquals("original-label",Files.readString(labels));assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=100"));
        assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }

    @Test void databaseFailureAfterPdfPublishReportsReconciliationAndDoesNotFreeTheKiz()throws Exception{
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("CREATE TRIGGER fail_ozon_consume BEFORE UPDATE OF status ON kiz_codes WHEN NEW.status='CONSUMED' BEGIN SELECT RAISE(ABORT,'simulated storage failure'); END");
        }
        Path labels=temporaryDirectory.resolve("published.pdf"),picking=temporaryDirectory.resolve("published-pick.pdf");
        var error=assertThrows(IOException.class,()->service(new AtomicBoolean()).exportAvailable(shop,List.of("POST-1"),labels.toFile(),picking.toFile(),null));
        assertEquals("OZON_PDF_PUBLISHED_RECONCILIATION_REQUIRED",error.getMessage());
        assertTrue(Files.exists(labels));assertTrue(Files.exists(picking));assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=100"));
        assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }

    @Test void missingKizJobAtCommitNeverConsumesOtherSelectedKiz()throws Exception{
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);
        var jobs=new OzonExemplarJobRepository();var job=jobs.find(1,"POST-1");var proof=OzonExemplarJobRepository.printProof(job,jobs.bindings(job.id()));
        assertThrows(IllegalStateException.class,()->jobs.consumePrintedKizBatch(1,Map.of("POST-1",proof,"POST-MISSING",proof)));
        assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=100"));assertEquals("VALIDATED",scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51"));
    }

    @Test void changedKizAfterRenderingIsNotConsumed()throws Exception{
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);
        var jobs=new OzonExemplarJobRepository();var job=jobs.find(1,"POST-1");var proof=OzonExemplarJobRepository.printProof(job,jobs.bindings(job.id()));
        try(var c=Database.getConnection();var statement=c.prepareStatement("UPDATE kiz_codes SET raw_code=? WHERE id=100")){statement.setString(1,"changed-mark");statement.executeUpdate();}
        assertThrows(IllegalStateException.class,()->jobs.consumePrintedKizBatch(1,Map.of("POST-1",proof)));
        assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=100"));assertEquals("VALIDATED",scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51"));
    }
    @Test void kizChangedDuringPdfRenderRequiresReconciliationAndKeepsReservation()throws Exception{
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),
                (owner,number)->{throw new AssertionError("Existing reservation must not be replaced");},
                (owner,number,target)->{Files.copy(officialPdf,target.toPath(),StandardCopyOption.REPLACE_EXISTING);try(var c=Database.getConnection();var statement=c.prepareStatement("UPDATE kiz_codes SET raw_code=? WHERE id=100")){statement.setString(1,"changed-mark");statement.executeUpdate();}catch(java.sql.SQLException e){throw new IOException(e);}return target;});
        var error=assertThrows(IOException.class,()->exporter.exportAvailable(shop,List.of("POST-1"),temporaryDirectory.resolve("changed.pdf").toFile(),temporaryDirectory.resolve("changed-pick.pdf").toFile(),null));
        assertEquals("OZON_PRINT_KIZ_PROOF_CHANGED",error.getMessage());assertFalse(Files.exists(temporaryDirectory.resolve("changed.pdf")));assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=100"));assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }
    @Test void unboundExemplarAddedDuringRenderingNeverPublishesPdfOrConsumesKiz()throws Exception{
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),
                (owner,number)->{throw new AssertionError("Existing reservation must not be replaced");},
                (owner,number,target)->{Files.copy(officialPdf,target.toPath(),StandardCopyOption.REPLACE_EXISTING);
                    try(var c=Database.getConnection();var statement=c.prepareStatement("INSERT INTO ozon_exemplars(job_id,shop_id,posting_number,item_index,product_id,exemplar_id,exemplar_index,kiz_id,updated_at) VALUES(51,1,'POST-1',0,'1001','extra',1,NULL,?)")){statement.setString(1,"2026-08-19T00:00:00Z");statement.executeUpdate();}catch(java.sql.SQLException e){throw new IOException(e);}return target;});
        Path labels=temporaryDirectory.resolve("unbound.pdf"),picking=temporaryDirectory.resolve("unbound-pick.pdf");
        var error=assertThrows(IOException.class,()->exporter.exportAvailable(shop,List.of("POST-1"),labels.toFile(),picking.toFile(),null));
        assertEquals("OZON_PRINT_KIZ_PROOF_CHANGED",error.getMessage());assertFalse(Files.exists(labels));assertFalse(Files.exists(picking));
        assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=100"));assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }

    @Test
    void printAllOmitsStandaloneProductLabelsIncludingPostingsWithoutKiz() throws Exception {
        seedPosting(1);
        seedAcceptedExemplars(List.of(RAW_KIZ));
        seedUnmarkedPosting("POST-2", "SKU-2", "offer-blue-68");
        new OzonProductKizPolicyRepository().setRequired(1, "SKU-2", false);
        new OzonCatalogRepository().upsertPage(1,List.of(new OzonProductDto("POST-2-PRODUCT",
                "offer-blue-68","SKU-2","Product SKU-2","",false,"",List.of("OZN0000002"))),"fixture");
        Path labels = temporaryDirectory.resolve("mixed-labels.pdf");
        Path picking = temporaryDirectory.resolve("mixed-picking.pdf");
        var progress = new java.util.ArrayList<Integer>();

        var exporter = new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),
                (owner,number)->{ throw new AssertionError("Do not prepare an accepted or exempt posting"); },
                (owner,number,target)->{
                    if(number.equals("POST-2")) writeOfficialTwoPagePdf(target.toPath(), "OZN0000002");
                    else Files.copy(officialPdf,target.toPath(),StandardCopyOption.REPLACE_EXISTING);
                    return target;
                });
        var result = exporter.exportAll(
                shop, List.of("POST-1", "POST-2"), labels.toFile(), picking.toFile(), progress::add);

        assertEquals(2, result.postingCount());
        assertEquals(1, result.kizPages());
        assertEquals(3, result.totalPages());
        assertEquals(List.of(1, 2), progress);
        try (PDDocument document = Loader.loadPDF(labels.toFile())) {
            assertEquals(3, document.getNumberOfPages());
            assertTrue(pageText(document, 0).contains("Official Ozon shipping page"));
            assertEquals(PRODUCT_BARCODE, decodeProductBarcode(document, 1, 203));
            assertTrue(pageText(document, 2).contains("Official Ozon shipping page"));
            assertFalse(new PDFTextStripper().getText(document).contains("Official Ozon product label"));
        }
        assertEquals("ACCEPTED", scalar("SELECT stage FROM ozon_exemplar_jobs WHERE id=51"));
    }

    @Test
    void batchPickingListContainsAllFortyOneItemsWithTheirPostingNumbers() throws Exception {
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
            assertTrue(text.contains("POST-1"));
            assertTrue(text.contains("POST-41"));
            assertTrue(text.contains("Product SKU-41"));
            assertTrue(text.contains("article-black-81"));
            assertPickingColumnOrder(text);
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                assertPageMillimeters(document, page, 210, 297);
            }
        }
    }

    @Test void thousandVerifiedCarrierPagesStreamWithoutWritingIndividualPdfs()throws Exception {
        List<String> numbers=java.util.stream.IntStream.range(0,1000).mapToObj(i->"FAST-"+i).toList();
        var selected=numbers.stream().map(number->new OzonPostingDto(number,number,number,"awaiting_deliver","","","","","","",
                new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,
                List.of(new OzonPostingItemDto(0,"FAST-PRODUCT","FAST-SKU","FAST-ARTICLE","Product "+number,1,"RUB","1")))).toList();
        new OzonPostingRepository().upsertPage(1,selected);new OzonProductKizPolicyRepository().setRequired(1,"FAST-SKU",false);
        var sourceFile=temporaryDirectory.resolve("shared-source.pdf");
        try(var pdf=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfWriter(sourceFile.toFile()))){
            var font=PdfFontFactory.createFont();
            for(int i=999;i>=0;i--)new com.itextpdf.kernel.pdf.canvas.PdfCanvas(pdf.addNewPage()).beginText().setFontAndSize(font,12).moveText(20,20).showText(numbers.get(i)).endText();
        }
        var source=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(sourceFile.toFile()));
        var downloader=new OzonPrintBundleService.OfficialLabelDownloader(){
            public java.io.File download(Shop owner,String number,java.io.File target){throw new AssertionError("Verified shared pages must not be written and opened for every posting");}
            public OzonPrintBundleService.VerifiedCarrierPage verifiedCarrierPage(Shop owner,String number){return new OzonPrintBundleService.VerifiedCarrierPage(source,1000-numbers.indexOf(number));}
            public void close(){source.close();}
        };
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),
                (owner,number)->new OzonPreparationResult(number,"NOT_REQUIRED",0,true,false,""),downloader);
        var labels=temporaryDirectory.resolve("fast-labels.pdf");var picking=temporaryDirectory.resolve("fast-picking.pdf");
        var progress=new java.util.ArrayList<Integer>();
        var result=exporter.exportAll(shop,numbers,labels.toFile(),picking.toFile(),progress::add);
        assertEquals(1000,result.postingCount());assertEquals(1000,result.totalPages());assertTrue(source.isClosed());
        assertEquals(java.util.stream.IntStream.rangeClosed(1,1000).boxed().toList(),progress);
        try(var output=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(labels.toFile()))){
            assertEquals(1000,output.getNumberOfPages());
            for(int i:new int[]{0,499,999})assertEquals(numbers.get(i),com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(output.getPage(i+1)).strip());
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

    @Test void sharedPdfWithoutIdentitiesStillExportsTheCorrectIsolatedCarrierAndCombinedKiz()throws Exception{
        isolatedBatchExport(false);
    }
    @Test void failedIsolatedLabelKeepsExistingPdfsAndDoesNotConsumeAnyKiz()throws Exception{
        isolatedBatchExport(true);
    }
    private void isolatedBatchExport(boolean failSecond)throws Exception{
        seedPosting(1);seedValidatedExemplar(RAW_KIZ);seedUnmarkedPosting("POST-2","SKU-2","offer-blue-68");
        new OzonProductKizPolicyRepository().setRequired(1,"SKU-2",false);
        try(var server=new okhttp3.mockwebserver.MockWebServer()){
            server.start();var labelsService=new OzonLabelService(new OzonLabelRepository(),(id,credentials)->new OzonApiClient(id,credentials,server.url("/"),new okhttp3.OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(5)).build(),new OzonApiRateLimiter(java.time.Duration.ZERO),new OzonRetryPolicy(1,java.time.Duration.ofMillis(1),java.time.Duration.ofMillis(1))));
            java.util.function.Function<String,okhttp3.mockwebserver.MockResponse> json=text->new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","application/json").setBody(text);
            server.enqueue(json.apply("{\"task_id\":7001}"));server.enqueue(json.apply("{\"status\":\"completed\",\"file_url\":\""+server.url("/cdn/shared.pdf")+"\"}"));server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody(new okio.Buffer().write(Files.readAllBytes(officialPdf))));
            for(int i=1;i<=2;i++){
                if(failSecond&&i==2){server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(500).setBody("{}"));break;}
                var path=temporaryDirectory.resolve("single-"+i+".pdf");
                try(var pdf=new PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(officialPdf.toFile()),new PdfWriter(path.toFile()))){new PdfCanvas(pdf.getFirstPage()).beginText().setFontAndSize(PdfFontFactory.createFont(),9).moveText(10,40).showText("POST-"+i).endText();}
                server.enqueue(json.apply("{\"result\":{\"task_id\":"+(8000+i)+"}}"));server.enqueue(json.apply("{\"result\":{\"status\":\"completed\",\"file_url\":\""+server.url("/cdn/"+i+".pdf")+"\"}}"));server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody(new okio.Buffer().write(Files.readAllBytes(path))));
            }
            var downloader=new OzonPrintBundleService.OfficialLabelDownloader(){
                public File download(Shop owner,String number,File target)throws IOException{return labelsService.downloadOfficialPdf(owner,number,target);}
                public OzonPrintBundleService.OfficialLabelDownloader forBatch(Shop owner,List<String> numbers){return new OzonBatchLabelDownloader(labelsService,owner,numbers);}
            };
            var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),(owner,number)->{throw new AssertionError("Reuse the validated KIZ reservation");},downloader);
            var labels=temporaryDirectory.resolve("isolated-batch.pdf");var picking=temporaryDirectory.resolve("isolated-picking.pdf");
            if(failSecond){
                Files.writeString(labels,"keep labels");Files.writeString(picking,"keep picking");
                assertThrows(IOException.class,()->exporter.exportAll(shop,List.of("POST-1","POST-2"),labels.toFile(),picking.toFile()));
                assertEquals("keep labels",Files.readString(labels));assertEquals("keep picking",Files.readString(picking));assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=100"));assertEquals("0",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
            }else{
                var result=exporter.exportAll(shop,List.of("POST-1","POST-2"),labels.toFile(),picking.toFile());assertEquals(4,result.totalPages());assertEquals(1,result.kizPages());
                try(var pdf=Loader.loadPDF(labels.toFile())){
                    assertTrue(pageText(pdf,0).contains("POST-1"));assertEquals(KizService.scannerSafeCode(RAW_KIZ),KizService.scannerSafeCode(decodeRenderedDataMatrixResult(pdf,1,203).getText()));
                    assertEquals(PRODUCT_BARCODE,decodeProductBarcode(pdf,1,203));assertTrue(pageText(pdf,2).contains("POST-2"));assertTrue(pageText(pdf,3).contains("Official Ozon product label"));
                }
                assertEquals("CONSUMED",scalar("SELECT status FROM kiz_codes WHERE id=100"));assertEquals("2",scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
            }
        }
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
                I18nService.getInstance().tr("ozon.picking.column.posting"),
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
        // The current purchased-KIZ guard requires the actual SKU/GTIN mapping;
        // seeding only a reservation does not prove which product the code belongs to.
        var posting = new OzonPostingRepository().find(1, "POST-1");
        assertNotNull(posting);
        new OzonProductGtinMappingRepository().put(1, posting.items().getFirst().sku(), "04645588781154");
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
        writeOfficialTwoPagePdf(target, PRODUCT_BARCODE);
    }

    private static void writeOfficialTwoPagePdf(Path target, String productBarcode) throws IOException {
        try (PdfDocument document = new PdfDocument(new PdfWriter(target.toFile()))) {
            var shipping = document.addNewPage(new PageSize(LABEL_WIDTH, LABEL_HEIGHT));
            new PdfCanvas(shipping).beginText().setFontAndSize(PdfFontFactory.createFont(), 8)
                    .moveText(10, 10).showText("Official Ozon shipping page 1").endText();
            // Synthetic Ozon carrier-number suffix, not the last digits of posting_number.
            new PdfCanvas(shipping).saveState().setFillColor(ColorConstants.BLACK)
                    .rectangle(102, 69, 53, 23).fill().setFillColor(ColorConstants.WHITE)
                    .beginText().setFontAndSize(PdfFontFactory.createFont(com.itextpdf.io.font.constants.StandardFonts.HELVETICA_BOLD),18)
                    .moveText(108,73).showText("2345").endText().restoreState();
            var product = document.addNewPage(new PageSize(LABEL_WIDTH, LABEL_HEIGHT));
            new PdfCanvas(product).beginText().setFontAndSize(PdfFontFactory.createFont(), 8)
                    .moveText(10, 90).showText("Official Ozon product label").endText();
            Barcode128 barcode = new Barcode128(document);
            barcode.setCode(productBarcode);
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
