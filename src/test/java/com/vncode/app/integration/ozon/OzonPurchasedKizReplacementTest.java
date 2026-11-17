package com.vncode.app.integration.ozon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.KizRejectedStock;
import com.vncode.app.features.kiz.KizSourceSelection;
import com.vncode.app.features.kiz.status.Kn3Mark;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic SQLite reservations only; no Ozon, Catalog, purchase or signing requests. */
class OzonPurchasedKizReplacementTest {
    private static final String GTIN = "04600000000001", OTHER_GTIN = "04600000000018";
    private static final KizSourceSelection PURCHASED = new KizSourceSelection(false, true);
    @TempDir Path directory;
    Shop shop;
    OzonPostingDto posting;
    OzonExemplarJobRepository jobs;
    OzonExemplarJob job;
    OzonExemplarJobRepository.KizBinding old;
    JsonObject payload;

    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
        shop = new Shop(1, "Synthetic Ozon", Marketplace.OZON, "fixture", "fixture");
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic Ozon','OZON','fixture','fixture'),(2,'Other Ozon','OZON','other','other')");
            s.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(1,'" + GTIN + "','Fixture','2026-01-01T00:00:00Z'),(1,'" + OTHER_GTIN + "','Other fixture','2026-01-01T00:00:00Z'),(2,'" + GTIN + "','Other owner','2026-01-01T00:00:00Z')");
            s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,1,'" + GTIN + "',20,'COMPLETED','2026-01-01','2026-01-01'),(2,1,'" + OTHER_GTIN + "',20,'COMPLETED','2026-01-01','2026-01-01'),(3,2,'" + GTIN + "',20,'COMPLETED','2026-01-01','2026-01-01')");
        }
        new OzonCatalogRepository().upsertPage(1, List.of(new OzonProductDto("9001", "fixture", "101", "Fixture", "", false, "", List.of())), "");
        new OzonProductGtinMappingRepository().put(1, "101", GTIN);
        posting = OzonJson.requirePostingDetail(JsonParser.parseString("{\"result\":{\"posting_number\":\"PARENT\",\"order_id\":\"ORDER\",\"status\":\"awaiting_packaging\",\"delivery_method\":{\"warehouse_id\":\"WH\"},\"products\":[{\"sku\":101,\"offer_id\":\"fixture\",\"name\":\"Fixture\",\"quantity\":1}],\"requirements\":{\"products_requiring_mandatory_mark\":[101]}}}").getAsJsonObject(), "PARENT");
        new OzonPostingRepository().upsertDetail(1, posting);
        addCode(1, 1, GTIN, "AVAILABLE", "IN_CIRCULATION", raw(GTIN, 1));
        jobs = new OzonExemplarJobRepository();
        var plan = OzonRequirementGuard.plan(posting, new OzonProductGtinMappingRepository().findResolvedBySku(1));
        job = jobs.stageLocalForPrint(1, plan, PURCHASED, () -> {}).job();
        jobs.persistRemoteExemplars(job, plan, List.of("7001"));
        job = jobs.find(1, "PARENT");
        old = jobs.bindings(job.id()).getFirst();
        payload = OzonExemplarService.exemplarPayload("PARENT", List.of(old), true);
        payload.getAsJsonArray("products").get(0).getAsJsonObject().getAsJsonArray("exemplars").get(0).getAsJsonObject().addProperty("gtd", "SYNTHETIC-GTD");
    }

    @AfterEach void close() { System.clearProperty("vncode.appdata.dir"); }

    @Test void paidRejectedMarkRotatesToExactCirculatedGtinAndPreservesGsAndRemoteSlot() throws Exception {
        String replacement = raw(GTIN, 2);
        addCode(2, 1, GTIN, "AVAILABLE", "IN_CIRCULATION", replacement);
        assertTrue(prepare(PURCHASED, rejected()));
        var bound = jobs.bindings(job.id()).getFirst();
        assertEquals(2, bound.kizId());
        assertEquals(old.productId(), bound.productId());
        assertEquals(old.exemplarId(), bound.exemplarId());
        assertEquals(replacement, bound.rawCode());
        var ticket = new OzonAcceptanceKizReplacement().active(shop, jobs.find(1, "PARENT"));
        assertNotNull(ticket); assertEquals("PREPARED", ticket.state());
        var exemplar = ticket.payload().getAsJsonArray("products").get(0).getAsJsonObject().getAsJsonArray("exemplars").get(0).getAsJsonObject();
        assertEquals("SYNTHETIC-GTD", exemplar.get("gtd").getAsString());
        assertEquals(replacement, exemplar.getAsJsonArray("marks").get(0).getAsJsonObject().get("mark").getAsString());
        assertEquals("CONSUMED/REJECTED", state(1));
        assertEquals("RESERVED/IN_CIRCULATION", state(2), "Candidate is tentative until authoritative readback, not consumed by replacement preparation");
    }

    @Test void paidSelectionExcludesWrongGtinOwnerLegalStateReservationImportedAndRejectedCis() throws Exception {
        addCode(2, 1, OTHER_GTIN, "AVAILABLE", "IN_CIRCULATION", raw(OTHER_GTIN, 2));
        addCode(3, 2, GTIN, "AVAILABLE", "IN_CIRCULATION", raw(GTIN, 3));
        addCode(4, 1, GTIN, "AVAILABLE", "RECEIVED", raw(GTIN, 4));
        addCode(5, 1, GTIN, "RESERVED", "IN_CIRCULATION", raw(GTIN, 5));
        addCode(6, 1, GTIN, "AVAILABLE", "IN_CIRCULATION", raw(GTIN, 6));
        imported(6, raw(GTIN, 6));
        addCode(7, 1, GTIN, "AVAILABLE", "IN_CIRCULATION", raw(OTHER_GTIN, 7));
        addCode(8, 1, GTIN, "AVAILABLE", "IN_CIRCULATION", raw(GTIN, 1).replace("CRYPTO-1", "CRYPTO-DUPLICATE"));
        addCode(9, 1, GTIN, "AVAILABLE", "IN_CIRCULATION", raw(GTIN, 9));
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("UPDATE kiz_codes SET block_id='synthetic-downloaded-block' WHERE id BETWEEN 1 AND 9");
        }
        assertTrue(prepare(new KizSourceSelection(true, true), rejected()));
        assertEquals(9, jobs.bindings(job.id()).getFirst().kizId());
        for (int id : List.of(2, 3, 4, 6, 7)) assertTrue(state(id).startsWith("AVAILABLE/"));
        assertEquals("RESERVED/IN_CIRCULATION", state(5));
        assertEquals("CONSUMED/REJECTED", state(8), "A rejected CIS cannot be retried through another crypto-tail occurrence");
    }

    @Test void noPaidCandidateStillQuarantinesDefinitivelyRejectedCodeWithoutCreatingAnIntent() throws Exception {
        assertFalse(prepare(PURCHASED, rejected()));
        assertEquals("CONSUMED/REJECTED", state(1));
        assertEquals(1, jobs.bindings(job.id()).getFirst().kizId());
        assertNull(new OzonAcceptanceKizReplacement().active(shop, job));
        try (var c = Database.getConnection()) { assertTrue(KizRejectedStock.contains(c, 1, old.rawCode())); }
    }

    @Test void mappingOrNativeOfferChangeNeverSelectsAnotherVariant() throws Exception {
        addCode(2, 1, GTIN, "AVAILABLE", "IN_CIRCULATION", raw(GTIN, 2));
        new OzonProductGtinMappingRepository().put(1, "101", OTHER_GTIN);
        assertFalse(prepare(PURCHASED, rejected()));
        assertEquals(1, jobs.bindings(job.id()).getFirst().kizId());
        assertEquals("AVAILABLE/IN_CIRCULATION", state(2));
        new OzonProductGtinMappingRepository().put(1, "101", GTIN);
        new OzonCatalogRepository().upsertPage(1, List.of(new OzonProductDto("9001", "changed-offer", "101", "Fixture", "", false, "", List.of())), "");
        assertFalse(prepare(PURCHASED, rejected()));
        assertEquals("AVAILABLE/IN_CIRCULATION", state(2));
    }

    @Test void pendingAndForeignOrderObservationsCannotBurnOrRotateAReservation() throws Exception {
        addCode(2, 1, GTIN, "AVAILABLE", "IN_CIRCULATION", raw(GTIN, 2));
        assertFalse(prepare(PURCHASED, new Kn3Mark("PARENT", old.productId(), old.exemplarId(), old.rawCode(), Kn3Mark.State.PENDING, "PROCESSING")));
        assertFalse(prepare(PURCHASED, new Kn3Mark("FOREIGN", old.productId(), old.exemplarId(), old.rawCode(), Kn3Mark.State.REJECTED, "INVALID_MARK")));
        assertEquals("RESERVED/IN_CIRCULATION", state(1));
        assertEquals("AVAILABLE/IN_CIRCULATION", state(2));
        assertNull(new OzonAcceptanceKizReplacement().active(shop, job));
    }

    @Test void credentialRotationStopsBeforeCandidateMutation() throws Exception {
        addCode(2, 1, GTIN, "AVAILABLE", "IN_CIRCULATION", raw(GTIN, 2));
        try (var c = Database.getConnection(); var s = c.createStatement()) { s.execute("UPDATE shops SET api_key='changed' WHERE id=1"); }
        assertThrows(SecurityException.class, () -> prepare(PURCHASED, rejected()));
        assertEquals("RESERVED/IN_CIRCULATION", state(1));
        assertEquals("AVAILABLE/IN_CIRCULATION", state(2));
    }

    @Test void explicitLegacyPaidReplacementPreservesDownloadedPoolForStrictDemands()throws Exception{
        addCode(2,1,GTIN,"AVAILABLE","IN_CIRCULATION",raw(GTIN,2));
        addCode(3,1,GTIN,"AVAILABLE","IN_CIRCULATION",raw(GTIN,3));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE kiz_codes SET block_id='synthetic-downloaded-block' WHERE id=2");}
        assertTrue(prepare(PURCHASED,rejected()));
        assertEquals(3,jobs.bindings(job.id()).getFirst().kizId(),"An explicitly reused legacy slot can use legacy stock without stealing the downloaded pool required by new paid slots");
        assertEquals("AVAILABLE/IN_CIRCULATION",state(2));
    }

    @Test void legacyImportedOrderWithoutEntryCannotBeReclassifiedAsPaidReplacement()throws Exception{
        addCode(2,1,GTIN,"AVAILABLE","IN_CIRCULATION",raw(GTIN,2));
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("UPDATE kiz_orders SET local_status='IMPORTED',external_order_id='invalid-import-external-id' WHERE id=1");
            s.execute("UPDATE kiz_codes SET block_id='stale-import-block' WHERE id IN(1,2)");
        }
        assertFalse(prepare(PURCHASED,rejected()));
        assertEquals(1,jobs.bindings(job.id()).getFirst().kizId());assertEquals("AVAILABLE/IN_CIRCULATION",state(2));
        assertNull(new OzonAcceptanceKizReplacement().active(shop,job));
    }

    @Test void downloadedPaidMarkWithNoSourceSnapshotKeepsDownloadedExactGtinProvenance() throws Exception {
        addCode(2, 1, GTIN, "AVAILABLE", "IN_CIRCULATION", raw(GTIN, 2));
        addCode(3, 1, GTIN, "AVAILABLE", "IN_CIRCULATION", raw(GTIN, 3));
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("UPDATE kiz_codes SET block_id='synthetic-downloaded-block' WHERE id IN (1,3)");
        }
        com.vncode.app.testsupport.CategoryStockFixture.add(shop, "9001", directory, 1);
        assertTrue(prepare(null, rejected()));
        assertEquals(3, jobs.bindings(job.id()).getFirst().kizId(), "Actual downloaded stock cannot fall back to a category or lose its downloaded provenance");
        assertEquals("AVAILABLE/IN_CIRCULATION", state(2), "Legacy unmarked stock is not a downloaded replacement in a default-source action");
        assertEquals(1, availableImports());
    }

    @Test void sellerOnlySourceCannotConvertAnActualDownloadedPaidBindingToCategoryStock() throws Exception {
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("UPDATE kiz_codes SET block_id='synthetic-downloaded-block' WHERE id=1");
        }
        com.vncode.app.testsupport.CategoryStockFixture.add(shop, "9001", directory, 1);
        assertFalse(prepare(KizSourceSelection.SELLER_ONLY, rejected()));
        assertEquals(1, jobs.bindings(job.id()).getFirst().kizId());
        assertEquals(1, availableImports());
        assertNull(new OzonAcceptanceKizReplacement().active(shop, job));
    }

    @Test void aTerminalDownloadedPaidJobCannotBeReopenedForReplacement() throws Exception {
        addCode(2, 1, GTIN, "AVAILABLE", "IN_CIRCULATION", raw(GTIN, 2));
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("UPDATE kiz_codes SET block_id='synthetic-downloaded-block' WHERE id IN (1,2)");
            s.execute("UPDATE ozon_exemplar_jobs SET stage='ACCEPTED' WHERE id=" + job.id());
        }
        assertFalse(prepare(null, rejected()));
        assertEquals(OzonExemplarJobStage.ACCEPTED, jobs.find(1, "PARENT").stage());
        assertEquals(1, jobs.bindings(job.id()).getFirst().kizId());
        assertEquals("RESERVED/IN_CIRCULATION", state(1), "A terminal paid job is not reopened or re-quarantined by replacement preparation");
        assertEquals("AVAILABLE/IN_CIRCULATION", state(2));
        assertNull(new OzonAcceptanceKizReplacement().active(shop, job));
    }

    @Test void aManualPurchasedDownloadWithoutBlockOrPipelineCannotUseCategoryFallback() throws Exception {
        addCode(2, 1, GTIN, "AVAILABLE", "IN_CIRCULATION", raw(GTIN, 2));
        addCode(3, 1, GTIN, "AVAILABLE", "IN_CIRCULATION", raw(GTIN, 3));
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(4,1,'" + GTIN + "',1,'COMPLETED','2026-01-01','2026-01-01')");
            s.execute("UPDATE kiz_codes SET order_id=4 WHERE id=2");
            s.execute("UPDATE kiz_orders SET external_order_id='synthetic-manual-download' WHERE id=1");
        }
        com.vncode.app.testsupport.CategoryStockFixture.add(shop, "9001", directory, 1);
        assertTrue(prepare(null, rejected()));
        assertEquals(3, jobs.bindings(job.id()).getFirst().kizId());
        assertEquals("AVAILABLE/IN_CIRCULATION", state(2));
        assertEquals(1, availableImports());
    }

    @Test void mixedSourcesDoNotReclassifyAnUnmarkedLegacyBindingAsPaidStock() throws Exception {
        addCode(2, 1, GTIN, "AVAILABLE", "IN_CIRCULATION", raw(GTIN, 2));
        com.vncode.app.testsupport.CategoryStockFixture.add(shop, "9001", directory, 1);
        assertTrue(prepare(new KizSourceSelection(true, true), rejected()));
        assertEquals(3, jobs.bindings(job.id()).getFirst().kizId());
        assertTrue(jobs.bindings(job.id()).getFirst().rawCode().startsWith("01" + com.vncode.app.testsupport.CategoryStockFixture.GTIN));
        assertEquals("AVAILABLE/IN_CIRCULATION", state(2));
    }

    private boolean prepare(KizSourceSelection sources, Kn3Mark mark) throws Exception {
        return new OzonAcceptanceKizReplacement().prepare(shop, posting, jobs.find(1, "PARENT"), List.of(mark), payload, oldHash(), sources, jobs);
    }
    private Kn3Mark rejected() { return new Kn3Mark("PARENT", old.productId(), old.exemplarId(), old.rawCode(), Kn3Mark.State.REJECTED, "INVALID_MARK"); }
    private String oldHash() { return Kn3Mark.fingerprint(old.rawCode()); }
    private static String raw(String gtin, int id) { return "01" + gtin + "21" + String.format(java.util.Locale.ROOT, "%013d", id) + '\u001d' + "91ABCD" + '\u001d' + "92SYNTHETIC-CRYPTO-" + id; }
    private void addCode(int id, int shopId, String gtin, String status, String legal, String code) throws Exception {
        try (var c = Database.getConnection(); var p = c.prepareStatement("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'2026-01-01','2026-01-01')")) {
            p.setInt(1, id); p.setInt(2, shopId); p.setInt(3, shopId == 2 ? 3 : gtin.equals(GTIN) ? 1 : 2); p.setString(4, code); p.setString(5, KizRejectedStock.identity(code)); p.setString(6, gtin); p.setString(7, status); p.setString(8, legal); p.executeUpdate();
        }
    }
    private void imported(int code, String raw) throws Exception {
        try (var c = Database.getConnection(); var p = c.prepareStatement("INSERT INTO kiz_import_batches(id,shop_id,owner,source,inn,file_hash,created_at) VALUES('synthetic-import',1,?,'fixture','','synthetic-file','2026-01-01')")) {
            p.setString(1, ListingIdentity.fingerprint(shop)); p.executeUpdate();
            try (var e = c.prepareStatement("INSERT INTO kiz_import_entries(code_id,batch_id,cis) VALUES(?,'synthetic-import',?)")) { e.setInt(1, code); e.setString(2, KizRejectedStock.identity(raw)); e.executeUpdate(); }
        }
    }
    private String state(int code) throws Exception {
        try (var c = Database.getConnection(); var p = c.prepareStatement("SELECT status,legal_status FROM kiz_codes WHERE id=?")) { p.setInt(1, code); try (var r = p.executeQuery()) { assertTrue(r.next()); return r.getString(1) + "/" + r.getString(2); } }
    }
    private int availableImports() throws Exception {
        try (var c = Database.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM kiz_codes k JOIN kiz_import_entries i ON i.code_id=k.id WHERE k.shop_id=1 AND k.status='AVAILABLE'")) { assertTrue(r.next()); return r.getInt(1); }
    }
}
