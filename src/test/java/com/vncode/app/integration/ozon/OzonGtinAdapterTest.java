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
        transport=new Fixture();adapter=new OzonGtinAdapter(shop,transport,id->{});
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    Preview preview()throws Exception{return new GtinPreviewService(new GtinSyncRepository(),k->adapter).create(key,Operation.ADD,"",GTIN);}
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
        var repo=new GtinSyncRepository();var runner=new GtinSyncCoordinator(repo,k->adapter,Clock.systemUTC(),1,Duration.ZERO);
        var job=runner.confirm(List.of(preview()));transport.loseResponse=true;
        runner.runPending(2,Marketplace.OZON);repo.recoverInterrupted();runner.runPending(2,Marketplace.OZON);
        assertEquals(1,transport.writes.get());assertEquals(Status.SUCCEEDED,repo.jobItems(job).getFirst().status());
    }
    @Test void changedSkuBeforeMutationAndAfterTimeoutCannotMatchOriginalPreview()throws Exception {
        var p=preview();transport.card.addProperty("sku",9002);
        assertThrows(GtinKnownUnsentException.class,()->adapter.submit(p));assertEquals(0,transport.writes.get());
        transport.card.getAsJsonArray("barcodes").add(GTIN);assertEquals(Verification.UNKNOWN,adapter.verify(p,new Submission("")));
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
            var repo=new GtinSyncRepository();var runner=new GtinSyncCoordinator(repo,k->adapter,Clock.systemUTC(),1,Duration.ZERO);var job=runner.confirm(List.of(preview()));
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
        var response=adapter.submit(p);assertEquals(Verification.REJECTED,adapter.verify(p,response));
        transport.card.getAsJsonArray("barcodes").add(GTIN);assertEquals(Verification.APPLIED,adapter.verify(p,response));assertEquals(1,transport.writes.get());
    }
    static final class Fixture implements ListingTransport {
        JsonObject card=JsonParser.parseString("{\"id\":501,\"offer_id\":\"fixture-offer\",\"sku\":9001,\"barcodes\":[\"legacy\"],\"is_archived\":false}").getAsJsonObject();
        JsonObject foreign,sent;AtomicInteger writes=new AtomicInteger();boolean apply=true,loseResponse;String response="{\"errors\":[]}";
        public JsonElement call(String path,JsonElement body,boolean mutation)throws IOException {
            if(mutation){assertEquals("v1/barcode/add",path);writes.incrementAndGet();sent=body.getAsJsonObject().deepCopy();if(apply)card.getAsJsonArray("barcodes").add(GTIN);if(loseResponse)throw new IOException("fixture timeout");return JsonParser.parseString(response);}
            if(path.equals("v3/product/list")){
                var items=new JsonArray();var item=new JsonObject();item.addProperty("product_id",501);items.add(item);
                if(foreign!=null){item=new JsonObject();item.addProperty("product_id",502);items.add(item);}
                var result=new JsonObject();result.add("items",items);var out=new JsonObject();out.add("result",result);return out;
            }
            assertEquals("v3/product/info/list",path);var items=new JsonArray();var ids=body.getAsJsonObject().getAsJsonArray("product_id");
            for(var id:ids){if(id.getAsString().equals("501"))items.add(card.deepCopy());else if(foreign!=null&&id.getAsString().equals("502"))items.add(foreign.deepCopy());}
            var out=new JsonObject();out.add("items",items);return out;
        }
    }
}
