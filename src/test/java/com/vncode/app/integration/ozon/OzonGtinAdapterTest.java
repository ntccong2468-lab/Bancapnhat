package com.vncode.app.integration.ozon;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.listing.ListingTransport;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static com.vncode.app.features.gtinsync.GtinWriteTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class OzonGtinAdapterTest {
    @TempDir Path root;Shop shop;Fixture transport;OzonGtinAdapter adapter;
    final ProductKey key=new ProductKey(2,Marketplace.OZON,"501","fixture-offer");
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=shop(Marketplace.OZON);
        new GtinSyncRepository().saveCatalog(shop.getId(),Marketplace.OZON,List.of(new RegisteredGtin(GTIN,"fixture-offer","Blue","52",true,true,RegisteredGtinProof.source(shop),Instant.now(),new com.vncode.app.features.catalogsync.CatalogProductData(java.util.Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published"),"tnved",List.of("6112110000")),List.of(),List.of()))));
        transport=new Fixture();adapter=new OzonGtinAdapter(shop,transport,id->{});
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    Preview preview()throws Exception{return new GtinPreviewService(new GtinSyncRepository(),k->adapter).create(key,Operation.ADD,"",GTIN);}
    @Test void actualReadbackUpdatesOnlyTheSelectedOwnedProductCache()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,name,sku,synced_at) VALUES(2,'501','fixture-offer','Fixture','9001','fixture'),(2,'502','other-offer','Sibling','9002','fixture')");
            s.execute("INSERT INTO ozon_product_barcodes(shop_id,product_id,barcode) VALUES(2,'502','sibling')");
        }
        var plan=preview();transport.apply=false;var response=adapter.submit(plan);
        assertEquals(Verification.PENDING,adapter.verify(plan,response));assertEquals(List.of("legacy"),cachedCodes("501"));
        transport.card.getAsJsonArray("barcodes").add(GTIN);
        assertEquals(Verification.APPLIED,adapter.verify(plan,response));assertTrue(cachedCodes("501").contains(GTIN));
        assertEquals(List.of("sibling"),cachedCodes("502"));
        assertEquals(Verification.APPLIED,adapter.verify(plan,response));
        assertTrue(cachedCodes("501").contains(GTIN),"The temporary fingerprint comparison must not overwrite observed barcodes");
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='changed' WHERE id=2");}
        assertThrows(SecurityException.class,()->MarketplaceBarcodeCache.observe(shop,key,List.of("foreign")));
        assertTrue(cachedCodes("501").contains(GTIN));
    }
    private List<String> cachedCodes(String product)throws Exception {
        var values=new java.util.ArrayList<String>();try(var c=Database.getConnection();var p=c.prepareStatement("SELECT barcode FROM ozon_product_barcodes WHERE shop_id=2 AND product_id=? ORDER BY barcode")){
            p.setString(1,product);try(var r=p.executeQuery()){while(r.next())values.add(r.getString(1));}
        }return values;
    }
    @Test void usesExactCurrentSkuAndOnlyBarcodeAddEndpoint()throws Exception {
        var p=preview();assertTrue(p.capability().add());assertFalse(p.capability().replace());
        var response=adapter.submit(p);assertEquals(1,transport.writes.get());
        var item=transport.sent.getAsJsonArray("barcodes").get(0).getAsJsonObject();
        assertEquals(GTIN,item.get("barcode").getAsString());assertEquals(9001,item.get("sku").getAsLong());
        assertEquals(List.of("legacy",GTIN),adapter.read(key).barcodes());assertEquals(Verification.APPLIED,adapter.verify(p,response));
        assertTrue(adapter.hasBarcode(key,"9001",GTIN));assertFalse(adapter.hasBarcode(key,"9002",GTIN));
    }
    @Test void http200DoesNotProveApplication()throws Exception {
        var p=preview();transport.apply=false;assertEquals(Verification.PENDING,adapter.verify(p,adapter.submit(p)));
    }
    @Test void timeoutThenRestartDoesNotReplayBarcodeAdd()throws Exception {
        var repo=new GtinSyncRepository();var runner=new GtinSyncCoordinator(repo,k->adapter,Clock.systemUTC(),1,Duration.ZERO,this::refreshFixture);
        var job=runner.confirm(List.of(preview()));transport.loseResponse=true;
        runner.runPending(2,Marketplace.OZON);repo.recoverInterrupted();runner.runPending(2,Marketplace.OZON);
        assertEquals(1,transport.writes.get());assertEquals(Status.SUCCEEDED,repo.jobItems(job).getFirst().status());
    }
    @Test void changedSkuBeforeMutationAndAfterTimeoutCannotMatchOriginalPreview()throws Exception {
        var p=preview();transport.card.addProperty("sku",9002);
        assertThrows(GtinKnownUnsentException.class,()->adapter.submit(p));assertEquals(0,transport.writes.get());
        transport.card.getAsJsonArray("barcodes").add(GTIN);assertEquals(Verification.UNKNOWN,adapter.verify(p,new Submission("")));
    }
    @Test void changedSkuDuringBarcodeLimiterWaitStopsBeforeAnyPost()throws Exception{
        adapter=new OzonGtinAdapter(shop,transport,id->transport.card.addProperty("sku",9002));var p=preview();var failure=assertThrows(GtinKnownUnsentException.class,()->adapter.submit(p));assertEquals(0,transport.writes.get());assertNotNull(failure.getCause());assertEquals("PRODUCT_CHANGED",failure.code());
    }
    @Test void batchFreshReadAfterLimiterWaitPreservesEveryOriginalBarcode()throws Exception{
        adapter=new OzonGtinAdapter(shop,transport,id->transport.card.add("barcodes",new JsonArray()));var p=preview();assertThrows(GtinKnownUnsentException.class,()->adapter.submitBatch(List.of(p),()->{}));assertEquals(0,transport.writes.get());
    }
    @Test void publishedGtinForAnotherExplicitSizeCannotBeAdded(){transport.size="54";assertThrows(IllegalArgumentException.class,this::preview);assertEquals(0,transport.writes.get());}
    @Test void missingExplicitColorDoesNotBecomeAnInferredVariantMatch(){transport.color="";assertThrows(IllegalArgumentException.class,this::preview);assertEquals(0,transport.writes.get());}
    @Test void changedColorDuringTheLimiterWaitIsCheckedAgainstTheActualCatalogVariant()throws Exception{
        adapter=new OzonGtinAdapter(shop,transport,id->transport.color="Red");var p=preview();assertThrows(GtinKnownUnsentException.class,()->adapter.submit(p));assertEquals(0,transport.writes.get());
    }
    @Test void aChangedVariantAfterAnAcceptedPostCannotBeReportedAsVerified()throws Exception{
        var p=preview();var submitted=adapter.submit(p);transport.color="Red";assertEquals(Verification.UNKNOWN,adapter.verify(p,submitted));assertFalse(adapter.hasBarcode(key,"9001",GTIN));assertEquals(1,transport.writes.get());
    }
    @Test void aLateLocalReservationConflictCannotBeRecordedAsADispatchedRequest()throws Exception{
        var otherKey=new ProductKey(shop.getId(),Marketplace.OZON,"502","other-offer");transport.foreign=JsonParser.parseString("{\"id\":502,\"offer_id\":\"other-offer\",\"sku\":9002,\"barcodes\":[\"other-legacy\"],\"is_archived\":false}").getAsJsonObject();
        var otherBefore=adapter.read(otherKey);try(var bulk=new com.vncode.app.integration.listing.GtinBatchTransport(shop,transport)){
            bulk.prepare(List.of(key));adapter=new OzonGtinAdapter(shop,bulk,id->{});var planned=preview();var competing=new Preview(otherBefore,Operation.ADD,"",GTIN,adapter.capability(otherKey));transport.afterFreshRead=()->bulk.reserve(List.of(competing));var markers=new AtomicInteger();
            var error=assertThrows(GtinKnownUnsentException.class,()->adapter.submitBatch(List.of(planned),markers::incrementAndGet));assertEquals("GTIN_ON_ANOTHER_PRODUCT",error.code());assertEquals(0,markers.get());assertEquals(0,transport.writes.get());
        }
    }
    @Test void theExplicitSellerSkuAliasStillNeedsAndMatchesTheRealColorAndSize()throws Exception{
        var target=new ProductKey(shop.getId(),Marketplace.OZON,"501","152DASH-52");transport.card.addProperty("offer_id",target.variantId());transport.color="Коричневый";
        new GtinSyncRepository().saveCatalog(shop.getId(),Marketplace.OZON,List.of(new RegisteredGtin(GTIN,"152 nau dashoa","Коричневый","52",true,true,RegisteredGtinProof.source(shop),Instant.now(),new com.vncode.app.features.catalogsync.CatalogProductData(java.util.Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published"),"tnved",List.of("6112110000")),List.of(),List.of()))));
        var preview=new GtinPreviewService(new GtinSyncRepository(),ignored->adapter).create(target,Operation.ADD,"",GTIN);assertEquals(Verification.APPLIED,adapter.verify(preview,adapter.submit(preview)));assertEquals(1,transport.writes.get());
    }
    @Test void explicitRussianSizeCanMatchButAnInternationalSizeCannotBeConverted()throws Exception{
        transport.size="L";transport.russianSize="52";var fields=new java.util.HashMap<String,List<String>>();fields.put("@catalog_id",List.of("91"));fields.put("@catalog_status",List.of("published"));fields.put("@size_type",List.of("РОССИЯ"));
        new GtinSyncRepository().saveCatalog(shop.getId(),Marketplace.OZON,List.of(new RegisteredGtin(GTIN,"fixture-offer","Blue","52",true,true,RegisteredGtinProof.source(shop),Instant.now(),new com.vncode.app.features.catalogsync.CatalogProductData(fields,List.of(),List.of()))));assertNotNull(preview());
        fields.remove("@size_type");new GtinSyncRepository().saveCatalog(shop.getId(),Marketplace.OZON,List.of(new RegisteredGtin(GTIN,"fixture-offer","Blue","52",true,true,RegisteredGtinProof.source(shop),Instant.now(),new com.vncode.app.features.catalogsync.CatalogProductData(fields,List.of(),List.of()))));assertThrows(IllegalArgumentException.class,this::preview);assertEquals(0,transport.writes.get());
    }
    @Test void skuOutsideTheOfficialInt64RangeCannotAuthorizeAWrite()throws Exception{
        transport.card.addProperty("sku","9223372036854775808");assertThrows(IOException.class,()->adapter.read(key));assertEquals(0,transport.writes.get());
    }
    @Test void allOldBarcodesMustRemainForAppliedResult()throws Exception {
        var p=preview();var replaced=new JsonArray();replaced.add(GTIN);transport.card.add("barcodes",replaced);
        assertEquals(Verification.UNKNOWN,adapter.verify(p,new Submission("")));
    }
    @Test void changedProductNamePreventsSendingAndCannotVerifyAnOldPreview()throws Exception {
        transport.card.addProperty("name","Original product");var p=preview();transport.card.addProperty("name","Different product");
        assertThrows(GtinKnownUnsentException.class,()->adapter.submit(p));assertEquals(0,transport.writes.get());
        transport.card.getAsJsonArray("barcodes").add(GTIN);assertEquals(Verification.UNKNOWN,adapter.verify(p,new Submission("")));
    }
    @Test void limitOf100CurrentBarcodesStopsBeforeMutation()throws Exception {
        var codes=new JsonArray();for(int i=0;i<100;i++)codes.add("legacy-"+i);transport.card.add("barcodes",codes);
        var p=preview();assertThrows(GtinKnownUnsentException.class,()->adapter.submit(p));assertEquals(0,transport.writes.get());
    }
    @Test void sameGtinOnOtherProductOrForeignOfferBlocksSending()throws Exception {
        transport.foreign=JsonParser.parseString("{\"id\":502,\"offer_id\":\"other\",\"sku\":9002,\"barcodes\":[\"00000017\"]}").getAsJsonObject();
        assertTrue(adapter.conflicts(key,GTIN));assertThrows(IllegalArgumentException.class,this::preview);
        assertThrows(IOException.class,()->adapter.read(new ProductKey(2,Marketplace.OZON,"501","foreign-offer")));assertEquals(0,transport.writes.get());
    }
    @Test void deniedLicenseDuringRateLimitWaitPausesKnownUnsentJob()throws Exception {
        var prior=LicenseRuntime.service();
        try(var denied=LicenseDomainFixture.denied(root.resolve("denied-license"))){
            adapter=new OzonGtinAdapter(shop,transport,id->LicenseRuntime.install(denied));
            var repo=new GtinSyncRepository();var runner=new GtinSyncCoordinator(repo,k->adapter,Clock.systemUTC(),1,Duration.ZERO,this::refreshFixture);var job=runner.confirm(List.of(preview()));
            try{runner.runPending(2,Marketplace.OZON);assertEquals(0,transport.writes.get());assertEquals(Status.QUEUED,repo.jobItems(job).getFirst().status());assertTrue(repo.paused(job));}
            finally{LicenseRuntime.install(prior);}
        }
    }
    @Test void unknownOrForeignResponseCannotMarkJobRejected()throws Exception {
        var p=preview();transport.apply=false;transport.response="{\"errors\":[{\"sku\":9999,\"barcode\":\"00000000000017\",\"code\":\"FIXTURE\"}]}";
        assertThrows(IOException.class,()->adapter.submit(p));assertEquals(Verification.PENDING,adapter.verify(p,new Submission("")));assertEquals(1,transport.writes.get());
    }
    @Test void exactRejectionHasNoReplayAndCanStillReconcileObservedSuccess()throws Exception {
        var p=preview();transport.apply=false;transport.response="{\"errors\":[{\"sku\":9001,\"barcode\":\"00000000000017\",\"code\":\"FIXTURE\"}]}";
        var response=adapter.submit(p);assertEquals("ozon-rejected:FIXTURE",response.remoteTaskId());assertEquals(Verification.REJECTED,adapter.verify(p,response));
        transport.card.getAsJsonArray("barcodes").add(GTIN);assertEquals(Verification.APPLIED,adapter.verify(p,response));assertEquals(1,transport.writes.get());
    }
    @Test void explicit429RetainsRetryAfterAndDoesNotMasqueradeAsAnUnknownAcceptedPost()throws Exception{
        var p=preview();transport.apply=false;transport.rateLimit=true;var error=assertThrows(GtinRateLimitedException.class,()->adapter.submit(p));assertEquals(Duration.ofSeconds(7),error.retryAfter());assertEquals("OZON_RATE_LIMIT",error.code());assertInstanceOf(com.vncode.app.integration.listing.ListingRateLimitException.class,error.getCause());assertEquals(1,transport.writes.get());assertEquals(Verification.PENDING,adapter.verify(p,new Submission("")));
    }
    @Test void anArchivedProductWithTheSameGtinAlsoBlocksSending()throws Exception{
        transport.foreign=JsonParser.parseString("{\"id\":502,\"offer_id\":\"archived\",\"sku\":9002,\"barcodes\":[\"00000017\"],\"is_archived\":true}").getAsJsonObject();transport.foreignArchived=true;assertTrue(adapter.conflicts(key,GTIN));assertThrows(IllegalArgumentException.class,this::preview);assertEquals(0,transport.writes.get());
    }
    private void refreshFixture(java.util.Map<ProductKey,String> cohort){assertEquals(java.util.Map.of(key,GTIN),cohort);RegisteredGtinProof.requireCurrent(shop,cohort.values());}
    static final class Fixture implements ListingTransport {
        JsonObject card=JsonParser.parseString("{\"id\":501,\"offer_id\":\"fixture-offer\",\"sku\":9001,\"barcodes\":[\"legacy\"],\"is_archived\":false}").getAsJsonObject();
        JsonObject foreign,sent;AtomicInteger writes=new AtomicInteger();boolean apply=true,loseResponse,rateLimit,foreignArchived;String color="Blue",size="52",russianSize="",response="{\"errors\":[]}";Runnable afterFreshRead=()->{};
        @Override public JsonElement call(String path,JsonElement body,boolean mutation,ListingTransport.CheckedGuard freshRead,Runnable beforeSend)throws IOException{freshRead.check();afterFreshRead.run();beforeSend.run();return call(path,body,mutation);}
        public JsonElement call(String path,JsonElement body,boolean mutation)throws IOException {
            if(mutation){assertEquals("v1/barcode/add",path);writes.incrementAndGet();sent=body.getAsJsonObject().deepCopy();if(rateLimit)throw new com.vncode.app.integration.listing.ListingRateLimitException(new okhttp3.Headers.Builder().add("Retry-After","7").build());if(apply)card.getAsJsonArray("barcodes").add(GTIN);if(loseResponse)throw new IOException("fixture timeout");return JsonParser.parseString(response);}
            if(path.equals("v3/product/list")){
                boolean archived=body.getAsJsonObject().getAsJsonObject("filter").get("visibility").getAsString().equals("ARCHIVED");var items=new JsonArray();var item=new JsonObject();if(!archived){item.addProperty("product_id",501);items.add(item);}
                if(foreign!=null&&foreignArchived==archived){item=new JsonObject();item.addProperty("product_id",502);items.add(item);}
                var result=new JsonObject();result.add("items",items);result.addProperty("total_items",items.size());var out=new JsonObject();out.add("result",result);return out;
            }
            if(path.equals("v4/product/info/attributes"))return JsonParser.parseString("{\"result\":[{\"id\":501,\"offer_id\":\""+card.get("offer_id").getAsString()+"\",\"name\":\"Fixture\",\"description_category_id\":77,\"type_id\":88,\"dimension_unit\":\"mm\",\"weight_unit\":\"g\",\"attributes\":[{\"id\":99,\"values\":[{\"value\":\""+color+"\"}]},{\"id\":100,\"values\":[{\"value\":\""+size+"\"}]},{\"id\":101,\"values\":[{\"value\":\""+russianSize+"\"}]},{\"id\":88,\"values\":[{\"value\":\"6112110000\"}]}]}]}");
            if(path.equals("v1/description-category/attribute"))return JsonParser.parseString("{\"result\":[{\"id\":99,\"name\":\"Цвет товара\"},{\"id\":100,\"name\":\"Размер производителя\"},{\"id\":101,\"name\":\"Российский размер\"},{\"id\":88,\"name\":\"ТН ВЭД\"}]}");
            assertEquals("v3/product/info/list",path);var items=new JsonArray();var ids=body.getAsJsonObject().getAsJsonArray("product_id");
            for(var id:ids){if(id.getAsString().equals("501"))items.add(card.deepCopy());else if(foreign!=null&&id.getAsString().equals("502"))items.add(foreign.deepCopy());}
            var out=new JsonObject();out.add("items",items);return out;
        }
    }
}
