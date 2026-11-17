package com.vncode.app.integration.ozon;

import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.*;
import com.vncode.app.features.kiz.status.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite/order planners; all purchase proposals are declined before signing or enqueue. */
class OzonRejectedPurchasedDemandTest {
    private static final String GTIN="04600000000008",OTHER="04600000000015";
    private static final KizSourceSelection PURCHASED=new KizSourceSelection(false,true);
    private static final String DETAIL="{\"result\":{\"posting_number\":\"POST-1\",\"order_id\":\"ORDER\",\"status\":\"awaiting_packaging\",\"delivery_method\":{\"warehouse_id\":\"WH\"},\"products\":[{\"sku\":101,\"offer_id\":\"fixture\",\"name\":\"Synthetic\",\"quantity\":3}],\"requirements\":{\"products_requiring_mandatory_mark\":[101]},\"ship_available\":true}}";
    @TempDir Path directory;
    Shop shop;
    OzonPostingDto posting;
    OzonExemplarJobRepository jobs;
    OzonExemplarJob job;
    List<OzonExemplarJobRepository.KizBinding> bindings;

    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        shop=new Shop(1,"Synthetic",Marketplace.OZON,"fixture","fixture");
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic','OZON','fixture','fixture')");
            s.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(1,'"+GTIN+"','Synthetic','2026-11-16T00:00:00Z'),(1,'"+OTHER+"','Other variant','2026-11-16T00:00:00Z')");
            s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,1,'"+GTIN+"',10,'COMPLETED','2026-11-16','2026-11-16')");
        }
        new OzonCatalogRepository().upsertPage(1,List.of(new OzonProductDto("9001","fixture","101","Synthetic","",false,"",List.of())),"");
        new OzonProductGtinMappingRepository().put(1,"101",GTIN);
        posting=OzonJson.requirePostingDetail(JsonParser.parseString(DETAIL).getAsJsonObject(),"POST-1");
        new OzonPostingRepository().upsertDetail(1,posting);
        for(int i=1;i<=3;i++)addCode(i);
        jobs=new OzonExemplarJobRepository();var plan=OzonRequirementGuard.plan(posting,new OzonProductGtinMappingRepository().findResolvedBySku(1));
        job=jobs.stageLocalForPrint(1,plan,PURCHASED,()->{}).job();
        jobs.persistRemoteExemplars(job,plan,List.of("7001","7002","7003"));
        job=jobs.find(1,"POST-1");bindings=jobs.bindings(job.id());
        observe(Kn3Mark.State.ACCEPTED,Kn3Mark.State.REJECTED,Kn3Mark.State.REJECTED);
        jobs.logAction(1,"accept_kiz","POST-1","rejected","INVALID_MARK",ListingIdentity.hash("fixture-request"));
    }
    @AfterEach void close(){System.clearProperty("vncode.appdata.dir");}

    @Test void countsOnlyExactRejectedPaidSlotsAndKeepsAcceptedSiblingOutOfDemand()throws Exception {
        var demand=demand();assertEquals(Map.of(GTIN,2),demand.quantities());
        assertFalse(demand.candidates().isEmpty());
        assertTrue(demand.candidates().stream().allMatch(p->p.nativeId().equals("9001")&&p.sku().equals("101")&&p.gtin().equals(GTIN)&&p.order().equals("POST-1")));
        assertFalse(demand.fingerprint().isBlank());
        assertEquals(demand.fingerprint(),demand().fingerprint(),"Read-only planning must be stable while the exact rejected slots are unchanged");
        assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=1"));
    }

    @Test void boundPrintRetryOffersOnlyRejectedDeficitMinusExistingPaidStockAndNeverBuysOnCancel()throws Exception {
        addCode(4);var proposal=new AtomicReference<KizPrintPurchaseSession.Proposal>();
        var session=new KizPrintPurchaseSession(shop,p->{proposal.set(p);return false;},s->{},()->false);
        assertThrows(CancellationException.class,()->session.run(()->{new OzonStockReplenishment().forPrint(shop,List.of("POST-1"),PURCHASED);return null;}));
        assertNotNull(proposal.get());var row=proposal.get().rows().getFirst();
        assertEquals(2,row.required());assertEquals(1,row.available());assertEquals(1,row.missing());assertEquals(1,row.buy());
        assertEquals(0,pipelines());assertEquals("AVAILABLE",scalar("SELECT status FROM kiz_codes WHERE id=4"));
    }

    @Test void acceptanceRetryRefreshesOrderAndOffersOnlyRejectedDeficit()throws Exception {
        addCode(4);var proposal=new AtomicReference<KizPrintPurchaseSession.Proposal>();
        try(var server=new MockWebServer()){
            server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(DETAIL));server.start();
            var preflight=new OzonAcceptanceStockReplenishment(owner->new OzonApiClient(owner.getId(),new OzonCredentials(owner.getClientId(),owner.getApiKey()),server.url("/"),new OkHttpClient.Builder().readTimeout(1,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))));
            assertThrows(CancellationException.class,()->preflight.run(shop,List.of("POST-1"),PURCHASED,p->{proposal.set(p);return false;},s->{},()->false));
            assertNotNull(proposal.get());assertEquals(2,proposal.get().rows().getFirst().required());assertEquals(1,proposal.get().quantity());
            assertEquals(1,server.getRequestCount());assertEquals("/v3/posting/fbs/get",server.takeRequest().getPath());assertEquals(0,pipelines());
        }
    }

    @Test void pendingOrAmbiguousSubmissionMustReconcileBeforeAnyReplacementDemand()throws Exception {
        for(String action:List.of("pending","submitted","ambiguous")){
            jobs.logAction(1,"accept_kiz","POST-1",action,"PENDING",ListingIdentity.hash(action));
            assertTrue(demand().quantities().isEmpty(),action+" is not a definitive refusal");
            var session=new KizPrintPurchaseSession(shop,p->{fail("Uncertain KIZ submission must not open a paid replacement proposal");return false;},s->{},()->false);
            session.run(()->{new OzonStockReplenishment().forPrint(shop,List.of("POST-1"),PURCHASED);return null;});
        }
        assertEquals(0,pipelines());
    }

    @Test void unfinishedPurchaseAfterTimeoutCannotOfferAnotherPaidLot()throws Exception {
        new ZnackRepository(new ZnackModels.ShopContext(1,"Synthetic")).enqueuePipeline(GTIN,2,"synthetic-pending-purchase");
        var proposal=new AtomicReference<KizPrintPurchaseSession.Proposal>();
        var session=new KizPrintPurchaseSession(shop,p->{proposal.set(p);return false;},s->{},()->false);
        assertThrows(CancellationException.class,()->session.run(()->{new OzonStockReplenishment().forPrint(shop,List.of("POST-1"),PURCHASED);return null;}));
        assertNotNull(proposal.get());assertEquals(2,proposal.get().rows().getFirst().required());
        assertEquals(0,proposal.get().quantity(),"The previous durable paid operation owns this deficit until reconciled");assertEquals(1,pipelines());
    }

    @Test void historicalAcceptedJobNeverCreatesPaidReplacementDemand()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE ozon_exemplar_jobs SET stage='ACCEPTED' WHERE id="+job.id());}
        assertTrue(demand().quantities().isEmpty());
        var session=new KizPrintPurchaseSession(shop,p->{fail("Historical printed/consumed slots cannot authorize new paid allocations");return false;},s->{},()->false);
        session.run(()->{new OzonStockReplenishment().forPrint(shop,List.of("POST-1"),PURCHASED);return null;});assertEquals(0,pipelines());
    }

    @Test void importedRejectedSlotDoesNotBecomePurchasedDemand()throws Exception {
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO kiz_import_batches(id,shop_id,owner,source,inn,file_hash,created_at) VALUES('synthetic-import',1,?,'fixture','','synthetic-hash','2026-11-16')")){
            p.setString(1,ListingIdentity.fingerprint(shop));p.executeUpdate();
            try(var e=c.prepareStatement("INSERT INTO kiz_import_entries(code_id,batch_id,cis) VALUES(2,'synthetic-import',?)")){e.setString(1,KizRejectedStock.identity(bindings.get(1).rawCode()));e.executeUpdate();}
        }
        assertEquals(Map.of(GTIN,1),demand().quantities());
        assertTrue(OzonPurchasedKizAssignment.rejectedDemand(shop,List.of(posting),KizSourceSelection.SELLER_ONLY).quantities().isEmpty());
    }

    @Test void staleMappingOrNativeOfferStopsBeforePurchaseProposal()throws Exception {
        new OzonProductGtinMappingRepository().put(1,"101",OTHER);
        assertEquals("OZON_PURCHASED_KIZ_IDENTITY_CHANGED",assertThrows(IOException.class,this::demand).getMessage());
        new OzonProductGtinMappingRepository().put(1,"101",GTIN);
        new OzonCatalogRepository().upsertPage(1,List.of(new OzonProductDto("9001","different-offer","101","Synthetic","",false,"",List.of())),"");
        assertEquals("OZON_PURCHASED_KIZ_IDENTITY_CHANGED",assertThrows(IOException.class,this::demand).getMessage());assertEquals(0,pipelines());
    }

    @Test void preparedSendingOrAwaitingReplacementCannotBuyAgain()throws Exception {
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_accept_kiz_replacements(job_id,owner,old_code_hash,new_code_hash,payload,state,remote_rejected,created_at,updated_at) VALUES(?,?,?,?,?,'PREPARED',1,'2026-11-16','2026-11-16')")){
            p.setLong(1,job.id());p.setString(2,ListingIdentity.fingerprint(shop));p.setString(3,Kn3Mark.fingerprint(bindings.get(1).rawCode()));p.setString(4,ListingIdentity.hash("synthetic-new-code"));p.setString(5,"{}");p.executeUpdate();
            for(String state:List.of("PREPARED","SENDING","AWAITING")){
                try(var update=c.prepareStatement("UPDATE ozon_accept_kiz_replacements SET state=? WHERE job_id=?")){update.setString(1,state);update.setLong(2,job.id());update.executeUpdate();}
                assertTrue(demand().quantities().isEmpty(),state+" owns an unfinished candidate");
            }
        }
        assertEquals(0,pipelines());
    }

    @Test void changedRejectedSlotsInvalidateFrozenPurchaseProofBeforeEnqueue()throws Exception {
        var expected=demand();assertEquals(Map.of(GTIN,2),expected.quantities());
        OzonPurchasedKizAssignment.requireUnchangedRejectedDemand(shop,List.of(posting),PURCHASED,expected);
        observe(Kn3Mark.State.ACCEPTED,Kn3Mark.State.ACCEPTED,Kn3Mark.State.REJECTED);
        assertThrows(IllegalStateException.class,()->OzonPurchasedKizAssignment.requireUnchangedRejectedDemand(shop,List.of(posting),PURCHASED,expected));assertEquals(0,pipelines());
    }

    private OzonPurchasedKizAssignment.RejectedDemand demand()throws IOException {return OzonPurchasedKizAssignment.rejectedDemand(shop,List.of(posting),PURCHASED);}
    private void observe(Kn3Mark.State... states){
        var marks=new ArrayList<Kn3Mark>();for(int i=0;i<states.length;i++){var b=bindings.get(i);marks.add(new Kn3Mark("POST-1",b.productId(),b.exemplarId(),b.rawCode(),states[i],states[i]==Kn3Mark.State.REJECTED?"INVALID_MARK":""));}
        new Kn3Repository().observe(shop,"POST-1",marks);
    }
    private void addCode(int id)throws Exception {
        String raw="01"+GTIN+"21"+String.format(Locale.ROOT,"%013d",id)+'\u001d'+"91ABCD"+'\u001d'+"92SYNTHETIC-CRYPTO-"+id;
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,block_id,status,legal_status,created_at,updated_at) VALUES(?,1,1,?,?,?,'SYNTHETIC-DOWNLOAD','AVAILABLE','IN_CIRCULATION','2026-11-16','2026-11-16')")){
            p.setInt(1,id);p.setString(2,raw);p.setString(3,KizRejectedStock.identity(raw));p.setString(4,GTIN);p.executeUpdate();
        }
    }
    private int pipelines()throws Exception {return Integer.parseInt(scalar("SELECT count(*) FROM znack_purchase_pipelines"));}
    private String scalar(String sql)throws Exception {try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
}
