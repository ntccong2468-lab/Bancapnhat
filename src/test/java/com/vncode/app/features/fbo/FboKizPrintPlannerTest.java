package com.vncode.app.features.fbo;

import com.vncode.app.config.Database;
import com.vncode.app.features.kizmapping.KizMappingRepository;
import com.vncode.app.features.kizmapping.ZnackGtinMappingSelection;
import com.vncode.app.integration.znack.ZnackModels.*;
import com.vncode.app.integration.znack.ZnackRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FboKizPrintPlannerTest {
    private static final String GTIN = "04610689132457";
    @TempDir Path temp;

    @AfterEach void clear() {
        System.clearProperty("vncode.appdata.dir");
    }

    @Test
    void reservesOneAtomicGtinPoolForFboPages() throws Exception {
        fixture(true, 2);
        FboPrintPlan plan = new FboKizPrintPlanner().plan(1, List.of(new FboBarcodePrintItem(product(true), 2)));
        assertEquals(6, plan.pages().size());
        assertEquals(List.of(
                        FboPrintPage.Kind.BARCODE, FboPrintPage.Kind.BARCODE, FboPrintPage.Kind.KIZ,
                        FboPrintPage.Kind.BARCODE, FboPrintPage.Kind.BARCODE, FboPrintPage.Kind.KIZ),
                plan.pages().stream().map(FboPrintPage::kind).toList());
        assertEquals(java.util.Arrays.asList(null, null, "KIZ-1", null, null, "KIZ-2"),
                plan.pages().stream().map(FboPrintPage::kizCode).toList());
        assertEquals(2, plan.usedKizs().size());
    }

    @Test
    void rejectsMissingMappingAndShortage() throws Exception {
        fixture(false, 2);
        assertThrows(IllegalStateException.class,
                () -> new FboKizPrintPlanner().plan(1, List.of(new FboBarcodePrintItem(product(true), 1))));

        clearDb();
        fixture(true, 1);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new FboKizPrintPlanner().plan(1, List.of(new FboBarcodePrintItem(product(true), 2))))
                .getMessage().contains(GTIN));
    }

    @Test
    void skipsInventoryForUnmarkedProduct() throws Exception {
        fixture(false, 0);
        FboPrintPlan plan = new FboKizPrintPlanner().plan(1, List.of(new FboBarcodePrintItem(product(false), 2)));
        assertEquals(4, plan.pages().size());
        assertTrue(plan.pages().stream().allMatch(page -> page.kind() == FboPrintPage.Kind.BARCODE));
        assertTrue(plan.pages().stream().allMatch(page -> page.kizCode() == null));
    }
    @Test void categoryStockPrintsMarketplaceBarcodeWithoutInventingARegisteredProductGtin()throws Exception{
        fixture(false,0);var shop=new com.vncode.app.models.Shop(1,"Shop","token");
        com.vncode.app.testsupport.CategoryStockFixture.add(shop,"1001",temp,2);
        var preflight=new FboPreflightService().inspect(shop,List.of(new FboBarcodePrintItem(product(true),2)));
        assertEquals(1,preflight.ready().size());assertTrue(preflight.toBuy().isEmpty());
        var plan=new FboKizPrintPlanner().plan(1,preflight.ready());assertEquals(2,plan.usedKizs().size());
        var orders=new FboBarcodePdfExporter().toOrders(plan.pages());assertEquals("SKU",orders.getFirst().getBarcode());
        assertTrue(plan.pages().getFirst().product().verifiedGtin().isBlank());
    }
    @Test void missingPurchaseMappingReleasesCategoryStockWhenFboBatchCannotBeCompleted()throws Exception{
        fixture(false,0);var shop=new com.vncode.app.models.Shop(1,"Shop","token");
        com.vncode.app.testsupport.CategoryStockFixture.add(shop,"1001",temp,1);
        assertThrows(IllegalStateException.class,()->new FboKizPrintPlanner().plan(1,List.of(new FboBarcodePrintItem(product(true),2))));
        assertEquals(1,new com.vncode.app.features.kiz.KizStockCategoryRepository().list(shop).getFirst().ready());
    }
    @Test void preflightAllocatesTheSharedPoolOnceAndDoesNotReserveBeforeConsent()throws Exception {
        fixture(true,2);var items=List.of(new FboBarcodePrintItem(product(true),3),new FboBarcodePrintItem(product(true),1));
        var result=new FboPreflightService().inspect(new com.vncode.app.models.Shop(1,"Shop","token"),items);
        assertEquals(1,result.ready().size());assertEquals(1,result.ready().getFirst().quantity());assertEquals(2,result.toBuy().get(GTIN));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM kiz_codes WHERE status='RESERVED'")){assertEquals(0,r.getInt(1));}
    }
    @Test void anotherSizeCannotInheritTheNmIdCategoryMapping()throws Exception {
        fixture(true,2);
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,2,1001,'44')");
            s.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,2,'SKU-44')");
        }
        var other=new FboProductSku(1001,"ART","Shoes","Brand","Product","Black","44","44","SKU-44","",true);
        var result=new FboPreflightService().inspect(new com.vncode.app.models.Shop(1,"Shop","token"),List.of(new FboBarcodePrintItem(product(true),1),new FboBarcodePrintItem(other,1)));
        assertEquals(1,result.ready().size());assertEquals(FboPreflightService.State.GTIN_UNVERIFIED,result.rows().getLast().state());
        assertThrows(IllegalStateException.class,()->new FboKizPrintPlanner().plan(1,List.of(new FboBarcodePrintItem(other,1))));
    }
    @Test void consumedFboMarksRemainRecoverableFromHistoryWhenFilePublicationFails()throws Exception {
        fixture(true,1);var plan=new FboKizPrintPlanner().plan(1,List.of(new FboBarcodePrintItem(product(true),1)));
        var shop=new com.vncode.app.models.Shop(1,"Shop","token");var exporter=new FboBarcodePdfExporter();var service=new com.vncode.app.features.print.history.PrintHistoryService();
        var template=new FboPrintTemplateService().getDefaultTemplate();
        long id=service.recordPreparedFboJob(shop,template,exporter.toOrders(plan.pages()));
        var impossible=java.nio.file.Files.createDirectory(temp.resolve("target-is-directory"));java.nio.file.Files.writeString(impossible.resolve("keep.txt"),"fixture");
        assertThrows(java.io.IOException.class,()->exporter.exportPlan(plan,impossible.toFile(),()->new com.vncode.app.integration.znack.ZnackGtinInventoryService().consumeForFboPrint(1,plan.usedKizs(),id)));
        var journal=service.getJobs(1).getFirst();assertEquals("pdf_ready",journal.status());assertTrue(journal.canReprint());
        assertFalse(service.getItems(id).toString().contains("KIZ-1"));
        new com.vncode.app.integration.znack.ZnackGtinInventoryService().releaseRecoverableReservations();
        assertEquals(0,new com.vncode.app.integration.znack.ZnackGtinInventoryService().availableCount(1,GTIN));
        var recovered=temp.resolve("recovered.pdf");service.reprint(journal,recovered.toFile(),temp.resolve("details.pdf").toFile());
        try(var pdf=org.apache.pdfbox.Loader.loadPDF(recovered.toFile())){assertEquals(3,pdf.getNumberOfPages());}
        assertEquals(0,new com.vncode.app.integration.znack.ZnackGtinInventoryService().availableCount(1,GTIN));
    }

    private void fixture(boolean mapping, int codeCount) throws Exception {
        System.setProperty("vncode.appdata.dir", temp.toString());
        Database.initDatabase();
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Shop','token')");
            st.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,subject_name,need_kiz,synced_at) VALUES(1,1001,'ART','Shoes',1,'now')");
            st.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,1,1001,'42')");
            st.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,1,'SKU'),(1,1,'"+GTIN+"')");
            st.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,1001,14177449,'Цвет','[\"Black\"]')");
        }
        if(mapping){
            var shop=new com.vncode.app.models.Shop(1,"Shop","token");
            com.vncode.app.features.gtinsync.GtinWriteTestFixture.saveSettings(shop,com.vncode.app.features.gtinsync.GtinWriteTestFixture.CERTIFICATE,com.vncode.app.integration.znack.ZnackModels.PRODUCTION_TRUE_API);
            var details=new com.vncode.app.features.catalogsync.CatalogProductData(java.util.Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),List.of(),List.of());
            new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(1,shop.getMarketplace(),List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(GTIN,"ART","Black","42",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(shop),java.time.Instant.now(),details)));
        }
        ZnackRepository repository = new ZnackRepository(new ShopContext(1, "Shop"));
        repository.upsertProducts(List.of(new Product(GTIN, "Shoes GTIN", null, null, null, null, null)));
        if (mapping) new KizMappingRepository().replaceRulesForGtin(1, GTIN,
                List.of(new ZnackGtinMappingSelection("Shoes", null, true)));
        long order = repository.createDraft(GTIN, Math.max(1, codeCount));
        for (int i = 1; i <= codeCount; i++) {
            repository.insertCodes(order, GTIN, new DownloadedCodes(List.of("KIZ-" + i), "block"));
        }
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE kiz_codes SET legal_status='IN_CIRCULATION' WHERE shop_id=1");
        }
    }

    private void clearDb() throws Exception {
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM shops");
        }
    }

    private FboProductSku product(boolean requiresKiz) {
        return new FboProductSku(1001, "ART", "Shoes", "Brand", "Product", "Black", "42", "42", "SKU", "", requiresKiz);
    }
}
