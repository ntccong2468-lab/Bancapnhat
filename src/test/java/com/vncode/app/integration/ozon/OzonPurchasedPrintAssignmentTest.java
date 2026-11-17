package com.vncode.app.integration.ozon;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.KizSourceSelection;
import com.vncode.app.features.kiz.status.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual production print preparation/SQLite with local Ozon HTTP; never a seller mutation. */
class OzonPurchasedPrintAssignmentTest {
    static final String GTIN="04600000000008";
    static final String RAW="01"+GTIN+"21PAID000000001\u001d91ABCD\u001d92ABC+/crypto";
    @TempDir Path directory;
    MockWebServer server;
    Shop shop;
    volatile JsonObject sent;
    final List<String> paths=new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile boolean pending,unknown,foreign,rejected,ambiguousSet,changeMapping;
    volatile int sets;

    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic','OZON','fixture','fixture')");
            s.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(1,'"+GTIN+"','Synthetic','2026-11-16T00:00:00Z')");
            s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,1,'"+GTIN+"',1,'COMPLETED','2026-11-16T00:00:00Z','2026-11-16T00:00:00Z')");
            try(var p=c.prepareStatement("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,block_id,status,legal_status,created_at,updated_at) VALUES(1,1,1,?,?,?,'PAID-BLOCK','AVAILABLE','IN_CIRCULATION','2026-11-16T00:00:00Z','2026-11-16T00:00:00Z')")){
                p.setString(1,RAW);p.setString(2,RAW.substring(0,RAW.indexOf(29)));p.setString(3,GTIN);p.executeUpdate();
            }
            s.execute("INSERT INTO ozon_product_gtin_mappings(shop_id,sku,gtin,created_at,updated_at) VALUES(1,'101','"+GTIN+"','2026-11-16T00:00:00Z','2026-11-16T00:00:00Z')");
        }
        new OzonCatalogRepository().upsertPage(1,List.of(new OzonProductDto("9001","fixture","101","Synthetic","",false,"",List.of())),"");
        shop=new Shop(1,"Synthetic",Marketplace.OZON,"fixture","fixture");
        server=new MockWebServer();server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){
            try{return respond(request);}catch(Exception failure){return new MockResponse().setResponseCode(500);}
        }});server.start();
    }
    @AfterEach void close()throws Exception{server.shutdown();System.clearProperty("vncode.appdata.dir");}

    @Test void newlyDownloadedPaidCodeNeedsExactOzonReadbackBeforeProductionPreparationSucceeds()throws Exception{
        var result=preparation(new KizSourceSelection(false,true)).prepare(shop,"POST-1");
        assertEquals("ACCEPTED",result.stage());assertTrue(result.shipReady());assertEquals(1,sets);
        assertTrue(paths.contains("/v5/fbs/posting/product/exemplar/validate"));
        assertTrue(paths.indexOf("/v6/fbs/posting/product/exemplar/set")<paths.indexOf("/v5/fbs/posting/product/exemplar/status"));
        assertEquals(RAW,sent.getAsJsonArray("products").get(0).getAsJsonObject().getAsJsonArray("exemplars").get(0).getAsJsonObject().getAsJsonArray("marks").get(0).getAsJsonObject().get("mark").getAsString(),"The GS/crypto tail is data, not a removable suffix");
        assertEquals("CONSUMED",scalar("SELECT status FROM kiz_codes WHERE id=1"));
        assertEquals(Kn3Mark.State.ACCEPTED,new Kn3Repository().read(shop,"POST-1").getFirst().state());
    }
    @Test void mixedSourceAndDefaultProductionEntryCannotBypassDownloadedPaidConfirmation()throws Exception{
        var result=preparation(new KizSourceSelection(true,true)).prepare(shop,"POST-1");
        assertEquals("ACCEPTED",result.stage());assertEquals(1,sets);
        // The persisted exact proof permits a subsequent no-source production reprint.
        assertEquals("ACCEPTED",preparation(null).prepare(shop,"POST-1").stage());assertEquals(1,sets);
    }
    @Test void defaultProductionEntryDetectsDownloadProvenanceWithoutSourceCheckbox()throws Exception{
        assertEquals("ACCEPTED",preparation(null).prepare(shop,"POST-1").stage());assertEquals(1,sets);
    }
    @Test void manualSuzDownloadWithoutBlockOrPipelineStillRequiresOzonConfirmation()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("UPDATE kiz_codes SET block_id=NULL WHERE id=1");
            s.execute("UPDATE kiz_orders SET external_order_id='issued-suz-order' WHERE id=1 AND shop_id=1");
        }
        assertEquals("ACCEPTED",preparation(null).prepare(shop,"POST-1").stage());assertEquals(1,sets);
    }
    @Test void mixedSourceChoiceDoesNotConvertUntypedLegacyLocalStockIntoPaidOrigin()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE kiz_codes SET block_id=NULL WHERE id=1");}
        service().openPrintBatch(shop,new KizSourceSelection(true,true)).stageForPrint("POST-1");
        assertEquals("VALIDATED",preparation(new KizSourceSelection(true,true)).prepare(shop,"POST-1").stage());assertEquals(0,sets);
    }
    @Test void importedLegacyOrderCannotAcquirePaidOriginFromAnInvalidExternalId()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE kiz_codes SET block_id=NULL WHERE id=1");}
        service().openPrintBatch(shop,new KizSourceSelection(true,true)).stageForPrint("POST-1");
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("UPDATE kiz_orders SET local_status='IMPORTED',external_order_id='invalid-import-remote-id' WHERE id=1");
        }
        assertEquals("VALIDATED",preparation(new KizSourceSelection(true,true)).prepare(shop,"POST-1").stage());assertEquals(0,sets);
    }
    @Test void heldImportedLegacyOrderWithAStaleDownloadMarkerIsStillCategoryProvenance()throws Exception{
        service().openPrintBatch(shop,new KizSourceSelection(true,true)).stageForPrint("POST-1");
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("UPDATE kiz_orders SET local_status='IMPORTED',external_order_id='invalid-import-remote-id' WHERE id=1");
        }
        assertEquals("VALIDATED",preparation(new KizSourceSelection(true,true)).prepare(shop,"POST-1").stage());assertEquals(0,sets);
    }
    @Test void successfulSetWithOnlyPendingStatusRetainsReservationAndIsNotPrintableSuccess()throws Exception{
        pending=true;
        assertThrows(IOException.class,()->preparation(new KizSourceSelection(false,true)).prepare(shop,"POST-1"));
        assertEquals(1,sets);assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=1"));
        assertNotEquals("ACCEPTED",new OzonExemplarJobRepository().find(1,"POST-1").stage().name());
        assertNotEquals("reconciled",new OzonExemplarJobRepository().latestActionStatus(1,"accept_kiz","POST-1"));
    }
    @Test void globalShipFlagWithoutExactMarksCannotConfirmPaidAssignment()throws Exception{
        unknown=true;
        assertThrows(IOException.class,()->preparation(new KizSourceSelection(false,true)).prepare(shop,"POST-1"));
        assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=1"));assertEquals(1,sets);
    }
    @Test void foreignProductPassedMarkCannotBorrowAValidCount()throws Exception{
        foreign=true;
        assertThrows(IOException.class,()->preparation(new KizSourceSelection(false,true)).prepare(shop,"POST-1"));
        assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=1"));
    }
    @Test void ambiguousSetReconcilesBeforeAnySecondMutationOrReplacement()throws Exception{
        ambiguousSet=true;
        assertThrows(OzonApiException.class,()->preparation(new KizSourceSelection(false,true)).prepare(shop,"POST-1"));
        assertEquals(1,sets);assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=1"));
        ambiguousSet=false;
        var recovered=preparation(new KizSourceSelection(false,true)).prepare(shop,"POST-1");
        assertTrue(recovered.shipReady());assertEquals(1,sets,"Unknown submission must be read back, never repeated");
        assertEquals(Kn3Mark.State.ACCEPTED,new Kn3Repository().read(shop,"POST-1").getFirst().state());
    }
    @Test void locallyPrintedAcceptedJobIsNotRemoteProofAndIsNeverReopenedToAssignConsumedStock()throws Exception{
        var service=service();var local=service.openPrintBatch(shop,new KizSourceSelection(false,true)).stageForPrint("POST-1");
        assertEquals("VALIDATED",local.stage());var jobs=new OzonExemplarJobRepository();jobs.consumePrinted(jobs.find(1,"POST-1"));
        unknown=true;
        assertThrows(IOException.class,()->preparation(new KizSourceSelection(false,true)).prepare(shop,"POST-1"));
        assertEquals(0,sets);assertEquals(OzonExemplarJobStage.ACCEPTED,jobs.find(1,"POST-1").stage());
        assertEquals("CONSUMED",scalar("SELECT status FROM kiz_codes WHERE id=1"));
    }
    @Test void laterDefinitiveRejectionInvalidatesAnEarlierAcceptedProof()throws Exception{
        assertEquals("ACCEPTED",preparation(new KizSourceSelection(false,true)).prepare(shop,"POST-1").stage());
        var jobs=new OzonExemplarJobRepository();var job=jobs.find(1,"POST-1");var bound=jobs.bindings(job.id()).getFirst();
        new Kn3Repository().observe(shop,"POST-1",List.of(new Kn3Mark("POST-1",bound.productId(),bound.exemplarId(),bound.rawCode(),Kn3Mark.State.REJECTED,"REJECTED")));
        rejected=true;
        assertThrows(IOException.class,()->preparation(new KizSourceSelection(false,true)).prepare(shop,"POST-1"));
        assertEquals(1,sets);assertEquals(OzonExemplarJobStage.ACCEPTED,jobs.find(1,"POST-1").stage());
    }
    @Test void mappingChangeBetweenValidationAndSetPreventsRemoteMutation()throws Exception{
        changeMapping=true;
        assertThrows(Exception.class,()->preparation(new KizSourceSelection(false,true)).prepare(shop,"POST-1"));
        assertTrue(paths.contains("/v5/fbs/posting/product/exemplar/validate"));
        assertEquals(0,sets);assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=1"));
    }
    @Test void bundleAcceptedFastPathStillRequiresAuthoritativePaidProofBeforeAnyPdfPublication()throws Exception{
        var service=service();service.openPrintBatch(shop,new KizSourceSelection(false,true)).stageForPrint("POST-1");
        var jobs=new OzonExemplarJobRepository();jobs.consumePrinted(jobs.find(1,"POST-1"));unknown=true;
        var bundle=new OzonPrintBundleService(new OzonPostingRepository(),jobs,
                OzonPrintBundleService.defaultPreparation(service,new KizSourceSelection(false,true)),
                (selected,number,target)->{fail("No PDF may be published for an unconfirmed paid binding");return target;});
        assertThrows(IOException.class,()->bundle.export(shop,"POST-1",directory.resolve("labels.pdf").toFile(),directory.resolve("picking.pdf").toFile()));
        assertEquals(0,sets);assertEquals(OzonExemplarJobStage.ACCEPTED,jobs.find(1,"POST-1").stage());
        assertFalse(java.nio.file.Files.exists(directory.resolve("labels.pdf")));
    }
    @Test void actualBundleRepairCannotReopenOrReplaceHistoricalConsumedPaidSlot()throws Exception{
        var prep=preparation(new KizSourceSelection(false,true));assertEquals("ACCEPTED",prep.prepare(shop,"POST-1").stage());
        var jobs=new OzonExemplarJobRepository();var job=jobs.find(1,"POST-1");var binding=jobs.bindings(job.id()).getFirst();
        new Kn3Repository().observe(shop,"POST-1",List.of(new Kn3Mark("POST-1",binding.productId(),binding.exemplarId(),binding.rawCode(),Kn3Mark.State.REJECTED,"REJECTED")));
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,block_id,status,legal_status,created_at,updated_at) VALUES(2,1,1,?,?,?,'PAID-BLOCK','AVAILABLE','IN_CIRCULATION','2026-11-16T00:00:00Z','2026-11-16T00:00:00Z')")){
            String next=RAW.replace("PAID000000001","PAID000000002");p.setString(1,next);p.setString(2,next);p.setString(3,GTIN);p.executeUpdate();
        }
        rejected=true;
        var bundle=new OzonPrintBundleService(new OzonPostingRepository(),jobs,prep,
                (selected,number,target)->{fail("A later remote rejection must block combined paid publication");return target;});
        assertThrows(IOException.class,()->bundle.export(shop,"POST-1",directory.resolve("labels.pdf").toFile(),directory.resolve("picking.pdf").toFile()));
        assertEquals(1,sets);assertEquals(OzonExemplarJobStage.ACCEPTED,jobs.find(1,"POST-1").stage());
        assertEquals(1,jobs.bindings(job.id()).getFirst().kizId());assertEquals("AVAILABLE",scalar("SELECT status FROM kiz_codes WHERE id=2"));
    }
    @Test void normalReceiptCannotResubmitPaidStockConsumedByAHistoricalLocalPrint()throws Exception{
        service().openPrintBatch(shop,new KizSourceSelection(false,true)).stageForPrint("POST-1");
        var jobs=new OzonExemplarJobRepository();jobs.consumePrinted(jobs.find(1,"POST-1"));
        var posting=new OzonPostingRepository().find(1,"POST-1");unknown=true;paths.clear();
        var api=new OzonApiClient(1,new OzonCredentials("fixture","fixture"),server.url("/"),new OkHttpClient.Builder().readTimeout(1,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1)));
        assertThrows(IOException.class,()->new OzonAcceptanceKizService(jobs).ensure(shop,posting,api,new KizSourceSelection(false,true)));
        assertEquals(0,sets);assertFalse(paths.contains("/v6/fbs/posting/product/exemplar/create-or-get"));
        assertFalse(paths.contains("/v5/fbs/posting/product/exemplar/validate"));assertEquals(OzonExemplarJobStage.ACCEPTED,jobs.find(1,"POST-1").stage());
    }
    @Test void normalReceiptRejectsMalformedPreviouslyHeldPaidIdentityBeforeAnyExemplarWrite()throws Exception{
        service().openPrintBatch(shop,new KizSourceSelection(false,true)).stageForPrint("POST-1");
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE kiz_codes SET raw_code=? WHERE id=1")){
            p.setString(1,RAW.replace(GTIN,"04600000000015"));p.executeUpdate();
        }
        var posting=new OzonPostingRepository().find(1,"POST-1");paths.clear();
        var api=new OzonApiClient(1,new OzonCredentials("fixture","fixture"),server.url("/"),new OkHttpClient.Builder().readTimeout(1,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1)));
        assertEquals("OZON_PURCHASED_KIZ_IDENTITY_CHANGED",assertThrows(IOException.class,()->new OzonAcceptanceKizService(new OzonExemplarJobRepository()).ensure(shop,posting,api,null)).getMessage());
        assertEquals(0,sets);assertTrue(paths.isEmpty());assertEquals("RESERVED",scalar("SELECT status FROM kiz_codes WHERE id=1"));
    }

    private OzonExemplarService service(){return new OzonExemplarService(new OzonPostingRepository(),new OzonProductGtinMappingRepository(),new OzonProductKizPolicyRepository(),new OzonExemplarJobRepository(),
            (id,credentials)->new OzonApiClient(id,credentials,server.url("/"),new OkHttpClient.Builder().readTimeout(1,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))));}
    private OzonPrintBundleService.Preparation preparation(KizSourceSelection source){return OzonPrintBundleService.defaultPreparation(service(),source);}
    private MockResponse respond(RecordedRequest request)throws Exception{
        String path=request.getPath();paths.add(path);var body=JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
        if(path.equals("/v3/posting/fbs/get"))return json("{\"result\":{\"posting_number\":\"POST-1\",\"order_id\":\"ORDER\",\"status\":\"awaiting_deliver\",\"delivery_method\":{\"warehouse_id\":\"WH\"},\"products\":[{\"sku\":101,\"offer_id\":\"fixture\",\"name\":\"Synthetic\",\"quantity\":1}],\"requirements\":{\"products_requiring_mandatory_mark\":[\"101\"]},\"available_actions\":[\"ship\"],\"ship_available\":true}}");
        if(path.equals("/v6/fbs/posting/product/exemplar/create-or-get"))return json("{\"result\":{\"products\":[{\"product_id\":101,\"exemplars\":[{\"exemplar_id\":7001}]}]}}");
        if(path.equals("/v5/fbs/posting/product/exemplar/validate")){
            if(changeMapping)try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE ozon_product_gtin_mappings SET gtin='04600000000015' WHERE shop_id=1 AND sku='101'");}
            return json("{\"products\":[{\"product_id\":101,\"exemplars\":[{\"valid\":true}]}]}");
        }
        if(path.equals("/v6/fbs/posting/product/exemplar/set")){sent=body.deepCopy();sets++;return ambiguousSet?new MockResponse().setResponseCode(503).setBody("{}"):json("{}");}
        if(path.equals("/v5/fbs/posting/product/exemplar/status")){
            if(unknown)return json("{\"result\":{\"ship_available\":true,\"products\":[]}}");
            var raw=sent==null?new JsonObject():sent.deepCopy();if(sent==null)return json("{\"result\":{\"ship_available\":true,\"products\":[]}}");
            raw.addProperty("ship_available",!pending&&!rejected);
            var product=raw.getAsJsonArray("products").get(0).getAsJsonObject();if(foreign)product.addProperty("product_id",202);
            var mark=product.getAsJsonArray("exemplars").get(0).getAsJsonObject().getAsJsonArray("marks").get(0).getAsJsonObject();
            mark.addProperty("check_status",pending?"processing":rejected?"failed":"passed");var errors=new JsonArray();if(rejected)errors.add("INVALID_MARK");mark.add("error_codes",errors);
            return json(raw.toString());
        }
        return new MockResponse().setResponseCode(404);
    }
    private String scalar(String sql)throws Exception{try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    private static MockResponse json(String body){return new MockResponse().setHeader("Content-Type","application/json").setBody(body);}
}
