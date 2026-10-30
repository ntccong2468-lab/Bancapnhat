package com.vncode.app.integration.ozon;

import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.KizSourceSelection;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic Ozon/KIZ data: source choice must survive split and retries. */
class OzonAcceptanceSourceSnapshotTest {
    @TempDir Path root;
    private OzonAcceptanceKizTest fixture;
    private OzonShipService service;
    @BeforeEach void setup() throws Exception {
        fixture=new OzonAcceptanceKizTest();fixture.directory=root;fixture.setup();
        service=new OzonShipService(new OzonPostingRepository(),new OzonProductGtinMappingRepository(),new OzonExemplarJobRepository(),
                (id,credentials)->new OzonApiClient(id,credentials,fixture.server.url("/"),new OkHttpClient.Builder().readTimeout(1,java.util.concurrent.TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))));
    }
    @AfterEach void close() throws Exception { fixture.close(); }

    @Test void sellerOnlyNeverUsesPurchasedKiz() throws Exception {
        var error=assertThrows(java.io.IOException.class,()->service.shipPerUnit(fixture.shop,"PARENT",true,KizSourceSelection.SELLER_ONLY));
        assertEquals("OZON_ACCEPT_KIZ_STOCK_REQUIRED",error.getMessage());
        assertTrue(fixture.sent.isEmpty());assertTrue(fixture.shipped.isEmpty());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM kiz_codes WHERE status='AVAILABLE'")){
            assertTrue(r.next());assertEquals(2,r.getInt(1));
        }
    }

    @Test void splitResumeKeepsFrozenSellerSourceEvenWhenCurrentSelectionChanges() throws Exception {
        fixture.multi=true;
        assertThrows(java.io.IOException.class,()->service.shipPerUnit(fixture.shop,"PARENT",true,KizSourceSelection.SELLER_ONLY));
        var receipts=new OzonUnitShipRepository();
        assertEquals(KizSourceSelection.SELLER_ONLY,receipts.find(fixture.shop,"PARENT").sources());
        var purchasedOnly=new KizSourceSelection(false,true);
        var parent=receipts.pendingParentOfChild(fixture.shop,"CHILD-A");
        assertNotNull(parent);assertEquals("PARENT",parent.source().postingNumber());
        var childPending=assertThrows(OzonApiException.class,()->service.shipPerUnit(fixture.shop,"CHILD-A",true,purchasedOnly));
        assertEquals("reconcile_required",childPending.kind());
        assertThrows(java.io.IOException.class,()->service.shipPerUnit(fixture.shop,"PARENT",true,purchasedOnly));
        assertTrue(fixture.sent.isEmpty());assertTrue(fixture.shipped.isEmpty());
        assertEquals(1,fixture.trace.stream().filter("/v1/posting/fbs/split"::equals).count());
        new OzonCatalogRepository().upsertPage(1,java.util.List.of(new OzonProductDto("9001","fixture","101","Fixture","",false,"",java.util.List.of())),"cursor");
        com.vncode.app.testsupport.CategoryStockFixture.add(fixture.shop,"9001",root,2);
        assertEquals("awaiting_deliver",service.shipPerUnit(fixture.shop,"PARENT",true,purchasedOnly).status());
        assertEquals(KizSourceSelection.SELLER_ONLY,receipts.find(fixture.shop,"PARENT").sources());
        assertEquals(2,fixture.sent.size());assertEquals(2,fixture.shipped.size());
        assertEquals(1,fixture.trace.stream().filter("/v1/posting/fbs/split"::equals).count());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM kiz_codes WHERE order_id=1 AND status='AVAILABLE'")){
            assertTrue(r.next());assertEquals(2,r.getInt(1),"Previously unselected purchased KIZ remain available");
        }
    }

    @Test void receiptMigrationPreservesOldOperationWithoutInventingSource() throws Exception {
        var source=OzonJson.requirePostingDetail(JsonParser.parseString("{\"result\":{\"posting_number\":\"PARENT\",\"order_id\":\"ORDER\",\"delivery_method\":{\"warehouse_id\":\"WH\"},\"status\":\"awaiting_packaging\",\"products\":[{\"sku\":101,\"quantity\":1}]}}").getAsJsonObject(),"PARENT");
        var receipts=new OzonUnitShipRepository();
        assertNull(receipts.find(fixture.shop,"PARENT"));
        assertTrue(receipts.begin(fixture.shop,source,"{}","fingerprint",1));
        receipts.update(fixture.shop,"PARENT","AMBIGUOUS",java.util.List.of("PARENT"));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("ALTER TABLE ozon_unit_ship_receipts DROP COLUMN source_mask");}
        var migrated=receipts.find(fixture.shop,"PARENT");
        assertNull(migrated.sources());assertEquals("AMBIGUOUS",migrated.state());
        assertEquals("fingerprint",migrated.fingerprint());assertEquals(java.util.List.of("PARENT"),migrated.children());
    }

    @Test void unknownSplitChildrenCannotStartIndependentAcceptance() throws Exception {
        var source=OzonJson.requirePostingDetail(JsonParser.parseString("{\"result\":{\"posting_number\":\"PARENT\",\"order_id\":\"ORDER\",\"delivery_method\":{\"warehouse_id\":\"WH\"},\"status\":\"awaiting_packaging\",\"products\":[{\"sku\":101,\"quantity\":2}]}}").getAsJsonObject(),"PARENT");
        var receipts=new OzonUnitShipRepository();assertNull(receipts.find(fixture.shop,"PARENT"));
        assertTrue(receipts.begin(fixture.shop,source,"{\"postings\":[]}","uncertain",2,KizSourceSelection.SELLER_ONLY));
        receipts.update(fixture.shop,"PARENT","AMBIGUOUS",null);
        assertNull(receipts.pendingParentOfChild(fixture.shop,"CHILD-A"),"IDs have not yet been received");
        var error=assertThrows(OzonApiException.class,()->service.shipPerUnit(fixture.shop,"CHILD-A",true,new KizSourceSelection(false,true)));
        assertEquals("reconcile_required",error.kind());assertTrue(fixture.sent.isEmpty());assertTrue(fixture.shipped.isEmpty());
        assertEquals(1,fixture.trace.stream().filter("/v3/posting/fbs/get"::equals).count());
    }
}
