package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.*;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.datamatrix.DataMatrixReader;
import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.status.Kn3Mark;
import com.vncode.app.features.kiz.status.Kn3Repository;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseGate;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AtomicFilePublisher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonKizOnlyPrintServiceTest {
    private static final String GTIN = "04645588781154";
    private static final String NOW = "2026-11-17T08:00:00Z";
    @TempDir Path directory;
    private Shop shop;

    @BeforeEach void prepare() throws Exception {
        System.setProperty("vncode.appdata.dir", directory.resolve("data").toString());
        Database.initDatabase();
        shop = new Shop(1, "Synthetic Ozon", Marketplace.OZON, "client", "fixture");
        sql("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic Ozon','OZON','client','fixture')");
        sql("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(1,'" + GTIN + "','Synthetic vest','" + NOW + "')");
    }
    @AfterEach void clean() { Thread.interrupted(); System.clearProperty("vncode.appdata.dir"); }

    @Test void missingCodesAreReportedWithoutPreparingPurchasingOrPublishingAnything() throws Exception {
        posting("POST-1", NOW, 1);
        var service = new OzonKizOnlyPrintService();
        var plan = service.inspect(shop, List.of("POST-1", "ABSENT"));
        assertEquals(2, plan.requestedCount()); assertEquals(0, plan.labelCount());
        assertEquals(Map.of("POST-1", OzonKizOnlyPrintService.Issue.KIZ_NOT_PREPARED,
                "ABSENT", OzonKizOnlyPrintService.Issue.POSTING_MISSING), plan.failures());
        assertThrows(IOException.class, () -> service.export(shop, plan, directory.resolve("empty.pdf").toFile()));
        assertEquals("0", scalar("SELECT count(*) FROM kiz_orders"));
        assertEquals("0", scalar("SELECT count(*) FROM ozon_exemplar_jobs"));
        assertEquals("0", scalar("SELECT count(*) FROM ozon_action_log"));
        assertEquals("0", scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
        assertFalse(Files.exists(directory.resolve("empty.pdf")));
    }

    @Test void localBatchUsesAcceptanceTimeAndPrintsEveryExemplarWithPostingIdentity() throws Exception {
        seed("POST-9", "2026-11-17T10:00:00Z", 1, 10);
        seed("POST-2", "2026-11-17T09:00:00+03:00", 2, 20);
        var service = new OzonKizOnlyPrintService();
        var plan = service.inspect(shop, List.of("POST-9", "POST-2"));
        assertEquals(List.of("POST-2", "POST-9"), plan.postingNumbers());
        assertEquals(3, plan.labelCount()); assertFalse(plan.orderingUncertain());
        Path output = directory.resolve("batch.pdf");
        var result = service.export(shop, plan, output.toFile());
        assertEquals(3, result.labels());
        try (var pdf = Loader.loadPDF(output.toFile())) {
            assertEquals(3, pdf.getNumberOfPages());
            var text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("#1 · POST-2 · 1/2")); assertTrue(text.contains("#1 · POST-2 · 2/2"));
            assertTrue(text.contains("#2 · POST-9")); assertTrue(text.contains("Art: ARTICLE"));
            assertTrue(text.contains("Size: 2XL")); assertTrue(text.contains("Color: black"));
            assertFalse(text.contains("OZN"), "Do not add a standalone Ozon product barcode");
            assertEquals(58 * 72 / 25.4, pdf.getPage(0).getMediaBox().getWidth(), .02);
            assertEquals(40 * 72 / 25.4, pdf.getPage(0).getMediaBox().getHeight(), .02);
            Files.createDirectories(Path.of("target/kiz-only-evidence"));
            Files.copy(output, Path.of("target/kiz-only-evidence/batch.pdf"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            for (int dpi : List.of(203, 300, 406)) {
                var image = new PDFRenderer(pdf).renderImageWithDPI(0, dpi);
                javax.imageio.ImageIO.write(image, "png", Path.of("target/kiz-only-evidence/label-" + dpi + ".png").toFile());
                float scale = dpi / 72f;
                // Decode the symbol with its quiet zone, independently of adjacent product text/footer.
                var symbol = image.getSubimage(Math.round(3 * scale), Math.round(14 * scale), Math.round(64 * scale), Math.round(64 * scale));
                var decoded = new DataMatrixReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(symbol))),
                        Map.of(com.google.zxing.DecodeHintType.TRY_HARDER, true));
                assertEquals(com.vncode.app.features.kiz.KizService.scannerSafeCode(code(20, 0)),
                        com.vncode.app.features.kiz.KizService.scannerSafeCode(decoded.getText()), "Preserve the complete synthetic CIS including GS fields");
                assertEquals("]d2", decoded.getResultMetadata().get(com.google.zxing.ResultMetadataType.SYMBOLOGY_IDENTIFIER));
            }
        }
        assertEquals("3", scalar("SELECT count(*) FROM kiz_codes WHERE status='CONSUMED'"));
        assertEquals("0", scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
        assertEquals("0", scalar("SELECT count(*) FROM ozon_action_log"));
        assertEquals("2", scalar("SELECT count(*) FROM ozon_postings WHERE status='awaiting_deliver'"));
        assertEquals("2", scalar("SELECT count(*) FROM kiz_orders"));
    }

    @Test void reprintKeepsOriginalConsumedCodesAndDoesNotBuyOrChangeCarrierPrintState() throws Exception {
        seed("POST-1", NOW, 1, 10);
        var service = new OzonKizOnlyPrintService();
        service.export(shop, service.inspect(shop, List.of("POST-1")), directory.resolve("first.pdf").toFile());
        String before = scalar("SELECT raw_code||'/'||status||'/'||consumed_at FROM kiz_codes");
        var previous = LicenseRuntime.service();
        try (var denied = LicenseDomainFixture.denied(directory.resolve("denied"))) {
            LicenseRuntime.install(denied);
            var result = service.export(shop, service.inspect(shop, List.of("POST-1")), directory.resolve("again.pdf").toFile());
            assertEquals(1, result.labels()); assertEquals(before, scalar("SELECT raw_code||'/'||status||'/'||consumed_at FROM kiz_codes"));
        } finally { LicenseRuntime.install(previous); }
        assertEquals("1", scalar("SELECT count(*) FROM kiz_orders"));
        assertEquals("0", scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
        assertEquals("0", scalar("SELECT count(*) FROM ozon_action_log"));
        assertEquals("awaiting_deliver", new OzonPostingRepository().find(1, "POST-1").status());
    }

    @Test void newPrintRequiresLicenseBeforeAnyPublicationOrConsumption() throws Exception {
        seed("POST-1", NOW, 1, 10); var service = new OzonKizOnlyPrintService();
        var plan = service.inspect(shop, List.of("POST-1")); var previous = LicenseRuntime.service();
        try (var denied = LicenseDomainFixture.denied(directory.resolve("denied"))) {
            LicenseRuntime.install(denied);
            assertThrows(LicenseGate.LicenseDeniedException.class, () -> service.export(shop, plan, directory.resolve("denied.pdf").toFile()));
        } finally { LicenseRuntime.install(previous); }
        assertFalse(Files.exists(directory.resolve("denied.pdf"))); assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes"));
    }

    @Test void changedCisAfterRenderingBlocksPublicationAndConsumption() throws Exception {
        seed("POST-1", NOW, 1, 10);
        var service = new OzonKizOnlyPrintService(() -> sql("UPDATE kiz_codes SET raw_code='changed'"), AtomicFilePublisher::publish);
        var plan = service.inspect(shop, List.of("POST-1"));
        assertThrows(IOException.class, () -> service.export(shop, plan, directory.resolve("changed.pdf").toFile()));
        assertFalse(Files.exists(directory.resolve("changed.pdf"))); assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes"));
        assertEquals("VALIDATED", scalar("SELECT stage FROM ozon_exemplar_jobs"));
    }

    @Test void changedOwnerAfterRenderingBlocksPublicationAndConsumption() throws Exception {
        seed("POST-1", NOW, 1, 10);
        var service = new OzonKizOnlyPrintService(() -> sql("UPDATE shops SET api_key='different-owner' WHERE id=1"), AtomicFilePublisher::publish);
        var plan = service.inspect(shop, List.of("POST-1"));
        assertThrows(IOException.class, () -> service.export(shop, plan, directory.resolve("foreign.pdf").toFile()));
        assertFalse(Files.exists(directory.resolve("foreign.pdf"))); assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes"));
    }

    @Test void differentShopCannotUseAReviewedPlan() throws Exception {
        seed("POST-1", NOW, 1, 10); var service = new OzonKizOnlyPrintService(); var plan = service.inspect(shop, List.of("POST-1"));
        sql("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(2,'Other','OZON','other','other')");
        var other = new Shop(2, "Other", Marketplace.OZON, "other", "other");
        assertThrows(IOException.class, () -> service.export(other, plan, directory.resolve("other.pdf").toFile()));
        assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes")); assertFalse(Files.exists(directory.resolve("other.pdf")));
    }

    @Test void failedFilePublicationDoesNotConsumeAndPreservesThePreviousTarget() throws Exception {
        seed("POST-1", NOW, 1, 10); Path target = directory.resolve("retained.pdf"); Files.writeString(target, "previous-content");
        var service = new OzonKizOnlyPrintService(() -> {}, (stage, destination) -> { throw new IOException("Synthetic disk failure"); });
        assertThrows(IOException.class, () -> service.export(shop, service.inspect(shop, List.of("POST-1")), target.toFile()));
        assertEquals("previous-content", Files.readString(target)); assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes"));
        assertEquals("VALIDATED", scalar("SELECT stage FROM ozon_exemplar_jobs"));
    }

    @Test void cancellationBeforePublicationDoesNotConsume() throws Exception {
        seed("POST-1", NOW, 1, 10);
        var service = new OzonKizOnlyPrintService(() -> Thread.currentThread().interrupt(), AtomicFilePublisher::publish);
        var plan = service.inspect(shop, List.of("POST-1"));
        try { assertThrows(IOException.class, () -> service.export(shop, plan, directory.resolve("cancel.pdf").toFile())); }
        finally { Thread.interrupted(); }
        assertFalse(Files.exists(directory.resolve("cancel.pdf"))); assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes"));
    }

    @Test void cooperativeCancellationBeforePublicationLeavesTheFileAndReservationUntouched() throws Exception {
        seed("POST-1", NOW, 1, 10); var cancellation = new OzonKizOnlyPrintService.Cancellation();
        var service = new OzonKizOnlyPrintService(() -> assertTrue(cancellation.request()), AtomicFilePublisher::publish);
        var failure = assertThrows(IOException.class, () -> service.export(shop, service.inspect(shop, List.of("POST-1")),
                directory.resolve("cooperative-cancel.pdf").toFile(), cancellation));
        assertEquals("OZON_KIZ_ONLY_CANCELLED", failure.getMessage());
        assertFalse(Files.exists(directory.resolve("cooperative-cancel.pdf")));
        assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes"));
        assertEquals("VALIDATED", scalar("SELECT stage FROM ozon_exemplar_jobs"));
    }

    @Test void cancellationInsideThePublisherIsDeclinedAndReportsTheTrueCommittedResult() throws Exception {
        seed("POST-1", NOW, 1, 10); var cancellation = new OzonKizOnlyPrintService.Cancellation();
        var service = new OzonKizOnlyPrintService(() -> {}, (staging, target) -> {
            assertFalse(cancellation.request(), "The output must not be reported cancelled after publication begins");
            assertFalse(cancellation.requested()); AtomicFilePublisher.publish(staging, target);
        });
        var target = directory.resolve("committed.pdf");
        var result = service.export(shop, service.inspect(shop, List.of("POST-1")), target.toFile(), cancellation);
        assertEquals(1, result.labels()); assertTrue(Files.exists(target));
        assertEquals("CONSUMED", scalar("SELECT status FROM kiz_codes"));
        assertEquals("ACCEPTED", scalar("SELECT stage FROM ozon_exemplar_jobs"));
        assertEquals("0", scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }

    @Test void paidCodeNeedsExactOzonAcceptanceInsteadOfTrustingAValidatedLocalJob() throws Exception {
        seed("POST-1", NOW, 1, 10);
        sql("UPDATE kiz_orders SET local_status='COMPLETED',external_order_id='synthetic-paid-order'");
        var plan = new OzonKizOnlyPrintService().inspect(shop, List.of("POST-1"));
        assertTrue(plan.postingNumbers().isEmpty());
        assertEquals(OzonKizOnlyPrintService.Issue.PAID_NOT_CONFIRMED, plan.failures().get("POST-1"));
        assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes")); assertEquals("0", scalar("SELECT count(*) FROM ozon_action_log"));
    }

    @Test void partialExemplarQuantityIsNotReportedAsACompletePosting() throws Exception {
        seed("POST-1", NOW, 1, 10); posting("POST-1", NOW, 2);
        var plan = new OzonKizOnlyPrintService().inspect(shop, List.of("POST-1"));
        assertEquals(0, plan.labelCount()); assertEquals(OzonKizOnlyPrintService.Issue.BINDING_CHANGED, plan.failures().get("POST-1"));
    }

    @Test void pendingKn3ReplacementStopsOnlyThatPostingWithoutSubmittingAnotherCode() throws Exception {
        seed("POST-1", NOW, 1, 10); seed("POST-2", "2026-11-17T09:00:00Z", 1, 20);
        var repository = new Kn3Repository();
        var mark = new Kn3Mark("POST-1", "1001", "1000", code(10, 0), Kn3Mark.State.REJECTED, "synthetic");
        repository.observe(shop, "POST-1", List.of(mark)); repository.begin(shop, mark, code(30, 0), "{}");
        var service = new OzonKizOnlyPrintService(); var plan = service.inspect(shop, List.of("POST-1", "POST-2"));
        assertEquals(List.of("POST-2"), plan.postingNumbers()); assertEquals(OzonKizOnlyPrintService.Issue.INVALID_KIZ, plan.failures().get("POST-1"));
        assertEquals("0", scalar("SELECT count(*) FROM ozon_action_log"));
    }

    @Test void missingOrEqualAcceptanceTimesAreExplicitlyUncertain() throws Exception {
        seed("POST-2", "", 1, 20); seed("POST-1", "", 1, 10);
        var plan = new OzonKizOnlyPrintService().inspect(shop, List.of("POST-2", "POST-1"));
        assertTrue(plan.orderingUncertain()); assertEquals(List.of("POST-1", "POST-2"), plan.postingNumbers());
        posting("POST-1", NOW, 1); posting("POST-2", NOW, 1);
        assertTrue(new OzonKizOnlyPrintService().inspect(shop, List.of("POST-2", "POST-1")).orderingUncertain());
    }

    @Test void duplicateSelectionAndRawCodeDiagnosticLeakAreRejected() throws Exception {
        seed("POST-1", NOW, 1, 10); var service = new OzonKizOnlyPrintService();
        assertThrows(IllegalArgumentException.class, () -> service.inspect(shop, List.of("POST-1", "POST-1")));
        assertFalse(service.inspect(shop, List.of("POST-1")).toString().contains(code(10, 0)));
    }

    @Test void reviewedPlanCannotMoveToAnIdenticalShopInAnotherDataProfile() throws Exception {
        seed("POST-1", NOW, 1, 10); var service = new OzonKizOnlyPrintService(); var plan = service.inspect(shop, List.of("POST-1"));
        System.setProperty("vncode.appdata.dir", directory.resolve("different-data").toString()); Database.initDatabase();
        sql("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic Ozon','OZON','client','fixture')");
        assertThrows(IOException.class, () -> service.export(shop, plan, directory.resolve("wrong-profile.pdf").toFile()));
        assertFalse(Files.exists(directory.resolve("wrong-profile.pdf")));
    }

    @Test void invalidCodeStopsOnlyItsPostingAndLeavesTheOtherLabelsPrintable() throws Exception {
        seed("POST-1", NOW, 1, 10); seed("POST-2", "2026-11-17T09:00:00Z", 1, 20);
        sql("UPDATE kiz_codes SET raw_code='invalid-synthetic-code' WHERE order_id=10");
        var service = new OzonKizOnlyPrintService(); var plan = service.inspect(shop, List.of("POST-1", "POST-2"));
        assertEquals(List.of("POST-2"), plan.postingNumbers());
        assertEquals(OzonKizOnlyPrintService.Issue.INVALID_KIZ, plan.failures().get("POST-1"));
        var result = service.export(shop, plan, directory.resolve("valid-subset.pdf").toFile());
        assertEquals(1, result.labels()); assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes WHERE order_id=10"));
        assertEquals("CONSUMED", scalar("SELECT status FROM kiz_codes WHERE order_id=20"));
    }

    @Test void exactPaidAcceptanceAllowsPrintAndReprintWithoutAnotherApiAction() throws Exception {
        seed("POST-1", NOW, 1, 10); confirmPaid("POST-1", 10);
        var service = new OzonKizOnlyPrintService();
        for (String file : List.of("paid.pdf", "paid-again.pdf")) {
            var plan = service.inspect(shop, List.of("POST-1"));
            assertTrue(plan.failures().isEmpty()); assertEquals(1, plan.labelCount());
            assertEquals(1, service.export(shop, plan, directory.resolve(file).toFile()).labels());
        }
        assertEquals("1", scalar("SELECT count(*) FROM ozon_action_log"), "Only the fixture's original exact readback receipt exists");
        assertEquals("1", scalar("SELECT count(*) FROM kiz_orders"));
        assertEquals("CONSUMED", scalar("SELECT status FROM kiz_codes"));
        assertEquals("0", scalar("SELECT count(*) FROM ozon_fbs_print_receipts"));
    }

    @Test void paidAcceptanceChangedAfterRenderingPreventsPublication() throws Exception {
        seed("POST-1", NOW, 1, 10); confirmPaid("POST-1", 10);
        var retired = new java.util.concurrent.atomic.AtomicReference<String>();
        var service = new OzonKizOnlyPrintService(() -> {
            new Kn3Repository().observe(shop, "POST-1", List.of(new Kn3Mark("POST-1", "1001", "1000", code(10, 0), Kn3Mark.State.REJECTED, "synthetic changed observation")));
            try { retired.set(scalar("SELECT status||'/'||legal_status FROM kiz_codes")); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }, AtomicFilePublisher::publish);
        var plan = service.inspect(shop, List.of("POST-1")); assertEquals(1, plan.labelCount());
        assertThrows(IOException.class, () -> service.export(shop, plan, directory.resolve("paid-changed.pdf").toFile()));
        assertFalse(Files.exists(directory.resolve("paid-changed.pdf")));
        assertEquals("CONSUMED/REJECTED", retired.get(), "The existing KN3 observer retires the rejected code itself");
        assertEquals(retired.get(), scalar("SELECT status||'/'||legal_status FROM kiz_codes"));
        assertEquals("VALIDATED", scalar("SELECT stage FROM ozon_exemplar_jobs"), "The failed PDF must not accept the local job");
    }

    @Test void storedCategoryStockPrintsWithoutSkuGtinMappingAndKeepsItsOriginalCodes() throws Exception {
        posting("POST-1", NOW, 2);
        com.vncode.app.testsupport.CategoryStockFixture.add(shop, "1001", directory, 2);
        var jobs = new OzonExemplarJobRepository();
        var required = OzonRequirementGuard.plan(new OzonPostingRepository().find(1, "POST-1"), Map.of(), java.util.Set.of(), java.util.Set.of("SKU-1"));
        var reserved = jobs.stageLocalForPrint(1, required, com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY, () -> {});
        var codes = jobs.bindings(reserved.job().id()).stream().map(OzonExemplarJobRepository.KizBinding::rawCode).toList();
        assertTrue(new OzonProductGtinMappingRepository().findResolvedBySku(1).isEmpty());
        var service = new OzonKizOnlyPrintService(); var plan = service.inspect(shop, List.of("POST-1"));
        assertTrue(plan.failures().isEmpty()); assertEquals(2, plan.labelCount());
        assertEquals(2, service.export(shop, plan, directory.resolve("category.pdf").toFile()).labels());
        assertEquals(codes, jobs.bindings(reserved.job().id()).stream().map(OzonExemplarJobRepository.KizBinding::rawCode).toList());
        assertEquals("2", scalar("SELECT count(*) FROM kiz_codes WHERE status='CONSUMED'"));
        assertEquals("0", scalar("SELECT count(*) FROM ozon_action_log"));
    }

    private void confirmPaid(String posting, int jobId) throws Exception {
        sql("UPDATE kiz_orders SET local_status='COMPLETED',external_order_id='synthetic-paid-order' WHERE id=" + jobId);
        new Kn3Repository().observe(shop, posting, List.of(new Kn3Mark(posting, "1001", Long.toString(jobId * 100L), code(jobId, 0), Kn3Mark.State.ACCEPTED, "passed")));
        OzonPurchasedKizAssignment.recordAcceptedReadback(shop, new OzonExemplarJobRepository().find(1, posting));
    }

    private void seed(String number, String accepted, int quantity, int id) throws Exception {
        posting(number, accepted, quantity);
        sql("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(" + id + ",1,'" + GTIN + "'," + quantity + ",'IMPORTED','" + NOW + "','" + NOW + "')");
        sql("INSERT INTO ozon_exemplar_jobs(id,shop_id,posting_number,stage,created_at,updated_at) VALUES(" + id + ",1,'" + number + "','VALIDATED','" + NOW + "','" + NOW + "')");
        new OzonProductGtinMappingRepository().put(1, "SKU-1", GTIN);
        try (var c = Database.getConnection(); var code = c.prepareStatement("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,reservation_token,created_at,updated_at) VALUES(?,1,?,?,?,'" + GTIN + "','RESERVED','IN_CIRCULATION',?,'" + NOW + "','" + NOW + "')");
                var exemplar = c.prepareStatement("INSERT INTO ozon_exemplars(job_id,shop_id,posting_number,item_index,product_id,exemplar_id,exemplar_index,kiz_id,check_status,updated_at) VALUES(?,1,?,0,'1001',?,?,?,'passed','" + NOW + "')")) {
            for (int i = 0; i < quantity; i++) {
                long codeId = id * 100L + i;
                code.setLong(1, codeId); code.setInt(2, id); code.setString(3, code(id, i)); code.setString(4, "SYNTHETIC"); code.setString(5, "ozon:" + id); code.executeUpdate();
                exemplar.setInt(1, id); exemplar.setString(2, number); exemplar.setString(3, Long.toString(codeId)); exemplar.setInt(4, i); exemplar.setLong(5, codeId); exemplar.executeUpdate();
            }
        }
    }

    private void posting(String number, String accepted, int quantity) {
        new OzonPostingRepository().upsertDetail(1, new OzonPostingDto(number, number + "-ORDER", number + "-ORDER", "awaiting_deliver", "", "warehouse",
                NOW, accepted, "", "", new OzonRequirements(List.of("1001"), List.of(), List.of()), List.of(), false,
                List.of(new OzonPostingItemDto(0, "1001", "SKU-1", "offer", "Synthetic vest", quantity, "RUB", "100"))));
        new OzonCatalogRepository().upsertPage(1, List.of(new OzonProductDto("1001", "offer", "SKU-1", "Synthetic vest", "", "ARTICLE", "black", "2XL", false, NOW, List.of("OZN0000001"))), "");
    }
    private static String code(int id, int index) { return "01" + GTIN + "21SERIAL-" + id + "-" + index + "\u001d91ABCD\u001d92SIGNATURE"; }
    private static void sql(String statement) { try (var c = Database.getConnection(); var s = c.createStatement()) { s.execute(statement); } catch (Exception failure) { throw new IllegalStateException(failure); } }
    private static String scalar(String statement) throws Exception { try (var c = Database.getConnection(); var s = c.createStatement(); var r = s.executeQuery(statement)) { assertTrue(r.next()); return r.getString(1); } }
}
