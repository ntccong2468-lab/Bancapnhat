package com.vncode.app.integration.ozon;

import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.testsupport.CategoryStockFixture;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The paid Ozon pool must count exactly the rows its strict allocator can reserve. */
class OzonPurchasedStockPolicyTest {
    private static final String GTIN="04600000000008",OTHER="04600000000015";
    private static final KizSourceSelection PURCHASED=new KizSourceSelection(false,true);
    @TempDir Path directory;
    Shop shop;

    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        shop=new Shop(1,"Synthetic",Marketplace.OZON,"fixture","fixture");
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic','OZON','fixture','fixture')");
            s.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(1,'"+GTIN+"','Synthetic','2026-11-16T00:00:00Z')");
            s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,1,'"+GTIN+"',10,'COMPLETED','2026-11-16','2026-11-16')");
        }
        new OzonCatalogRepository().upsertPage(1,List.of(new OzonProductDto("9001","fixture","101","Synthetic","",false,"",List.of())),"");
        new OzonProductGtinMappingRepository().put(1,"101",GTIN);
        addCode(1,raw(OTHER,1),"SYNTHETIC-DOWNLOAD","IN_CIRCULATION");
        addCode(2,raw(GTIN,2),null,"IN_CIRCULATION");
        addCode(3,raw(GTIN,3),"SYNTHETIC-DOWNLOAD","RECEIVED");
        addCode(4,raw(GTIN,4),"SYNTHETIC-DOWNLOAD","IN_CIRCULATION");
    }
    @AfterEach void close(){System.clearProperty("vncode.appdata.dir");}

    @Test void strictCountIgnoresMalformedUntypedAndNotCirculatedRows()throws Exception {
        assertEquals(1,policy(2).counts(shop,List.of(GTIN)).getOrDefault(GTIN,0),
                "A DB GTIN alone cannot turn a malformed or untyped row into downloaded paid Ozon stock");
        assertEquals("AVAILABLE",state(1));assertEquals("AVAILABLE",state(2));assertEquals("AVAILABLE",state(3));assertEquals("AVAILABLE",state(4));
    }

    @Test void strictSelectionSkipsOlderIneligibleRowsWithoutChangingGsOrCrypto()throws Exception {
        try(var c=Database.getConnection()){
            assertEquals(List.of(4L),OzonPurchasedKizStock.select(c,1,GTIN,1,ListingIdentity.fingerprint(shop),true));
            assertEquals(List.of(4L),OzonPurchasedKizStock.select(c,1,GTIN,2,ListingIdentity.fingerprint(shop),true));
        }
        assertEquals("AVAILABLE",state(4),"Read-only selection must not reserve outside the repository transaction");
    }

    @Test void strictReservationUsesEligibleDownloadedRowAfterSkippingOlderBadStock()throws Exception {
        var plan=plan(1);var jobs=new OzonExemplarJobRepository();
        var staged=jobs.stageLocalForPrint(1,plan,PURCHASED,()->{},()->{},true);
        assertTrue(staged.ready());var bound=jobs.bindings(staged.job().id());assertEquals(1,bound.size());
        assertEquals(4,bound.getFirst().kizId());assertEquals(raw(GTIN,4),bound.getFirst().rawCode());
        assertEquals("RESERVED",state(4));assertEquals("AVAILABLE",state(1));assertEquals("AVAILABLE",state(2));
    }

    @Test void ineligibleRowsCannotMaskTheExplicitPaidProposalShortage()throws Exception {
        // This row is unavailable, not an unfinished circulation that should defer a paid buy.
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE kiz_codes SET status='CONSUMED' WHERE id=3");}
        var quantities=Map.of(GTIN,2);var availability=policy(2);
        var proposed=new AtomicReference<KizPrintPurchaseSession.Proposal>();
        var session=new KizPrintPurchaseSession(shop,p->{proposed.set(p);return false;},s->{},()->false);
        assertThrows(CancellationException.class,()->session.run(()->new KizStockReplenisher().ensurePurchased(shop,quantities,missing->fail("Declined proposal must stop before paid prerequisite validation"),availability)));
        assertNotNull(proposed.get());var row=proposed.get().rows().getFirst();
        assertEquals(2,row.required());assertEquals(1,row.available());assertEquals(1,row.missing());assertEquals(1,row.buy());
        assertEquals("0",scalar("SELECT count(*) FROM znack_purchase_pipelines"));assertEquals("AVAILABLE",state(4));
    }

    @Test void legacyRepositoryEntryStillReservesUntypedLegacyStock()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE kiz_codes SET status='CONSUMED' WHERE id IN(1,4)");}
        var jobs=new OzonExemplarJobRepository();var staged=jobs.stageLocalForPrint(1,plan(1),PURCHASED,()->{},()->{});
        assertTrue(staged.ready());assertEquals(2,jobs.bindings(staged.job().id()).getFirst().kizId(),
                "The opt-in Ozon paid policy must not rewrite the historical repository contract");
    }

    @Test void mixedSourceAllocationPreservesCategoryStockWithoutCountingItAsPaid()throws Exception {
        CategoryStockFixture.add(shop,"9001",directory,1);
        assertEquals(1,policy(2).counts(shop,List.of(GTIN)).getOrDefault(GTIN,0));
        var jobs=new OzonExemplarJobRepository();var staged=jobs.stageLocalForPrint(1,plan(2),new KizSourceSelection(true,true),()->{},()->{},true);
        assertTrue(staged.ready());var bindings=jobs.bindings(staged.job().id());assertEquals(2,bindings.size());
        assertTrue(bindings.stream().anyMatch(b->b.kizId()==4));
        assertTrue(bindings.stream().anyMatch(b->b.rawCode().startsWith("01"+CategoryStockFixture.GTIN)),
                "Imported category allocation keeps its seller-approved GTIN-relaxed rule");
        assertEquals("AVAILABLE",state(2));
    }

    private KizPurchaseAvailability policy(int required)throws Exception {
        return OzonPurchasedKizStock.forDemand(shop,Map.of(GTIN,required),OzonPurchasedKizAssignment.rejectedDemand(shop,List.of(),PURCHASED));
    }
    private OzonRequirementGuard.PreparationPlan plan(int quantity)throws Exception {
        String detail="{\"result\":{\"posting_number\":\"POST-1\",\"order_id\":\"ORDER\",\"status\":\"awaiting_packaging\",\"delivery_method\":{\"warehouse_id\":\"WH\"},\"products\":[{\"sku\":101,\"offer_id\":\"fixture\",\"name\":\"Synthetic\",\"quantity\":"+quantity+"}],\"requirements\":{\"products_requiring_mandatory_mark\":[101]}}}";
        var posting=OzonJson.requirePostingDetail(JsonParser.parseString(detail).getAsJsonObject(),"POST-1");
        new OzonPostingRepository().upsertDetail(1,posting);
        return OzonRequirementGuard.plan(posting,new OzonProductGtinMappingRepository().findResolvedBySku(1));
    }
    private void addCode(int id,String code,String block,String legal)throws Exception {
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,block_id,status,legal_status,created_at,updated_at) VALUES(?,1,1,?,?,?,?,'AVAILABLE',?,'2026-11-16','2026-11-16')")){
            p.setInt(1,id);p.setString(2,code);p.setString(3,KizRejectedStock.identity(code));p.setString(4,GTIN);p.setString(5,block);p.setString(6,legal);p.executeUpdate();
        }
    }
    private static String raw(String gtin,int id){return "01"+gtin+"21"+String.format(Locale.ROOT,"%013d",id)+'\u001d'+"91ABCD"+'\u001d'+"92SYNTHETIC-CRYPTO-"+id;}
    private String state(int code)throws Exception{return scalar("SELECT status FROM kiz_codes WHERE id="+code);}
    private String scalar(String sql)throws Exception {try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
}
