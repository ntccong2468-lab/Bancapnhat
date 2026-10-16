package com.vncode.app.integration.wb;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
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

class WbGtinAdapterTest {
    @TempDir Path root;
    Shop shop;Fixture transport;WbGtinAdapter adapter;
    final ProductKey key=new ProductKey(1,Marketplace.WILDBERRIES,"101","11");
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        shop=shop(Marketplace.WILDBERRIES);transport=new Fixture();adapter=new WbGtinAdapter(shop,transport);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    Preview preview()throws Exception{return new GtinPreviewService(new GtinSyncRepository(),k->adapter).create(key,Operation.ADD,"",GTIN);}
    @Test void additionPreservesCompleteCardAndOtherSizeBarcodes()throws Exception {
        var p=preview();assertTrue(p.capability().add());assertFalse(p.capability().replace());
        var submitted=adapter.submit(p);assertEquals(1,transport.writes.get());
        var card=transport.sent.get(0).getAsJsonObject();
        assertEquals("Keep title",card.get("title").getAsString());assertTrue(card.get("kizMarked").getAsBoolean());
        assertEquals("Keep description",card.get("description").getAsString());
        assertEquals(List.of("legacy",GTIN),card.getAsJsonArray("sizes").get(0).getAsJsonObject().getAsJsonArray("skus").asList().stream().map(JsonElement::getAsString).toList());
        assertEquals("other-size",card.getAsJsonArray("sizes").get(1).getAsJsonObject().getAsJsonArray("skus").get(0).getAsString());
        assertEquals("owned-document",card.getAsJsonObject("documents").getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString());
        assertFalse(card.getAsJsonObject("dimensions").has("isValid"));assertFalse(card.getAsJsonObject("documents").has("overallVerdict"));
        assertFalse(card.getAsJsonObject("documents").getAsJsonArray("items").get(0).getAsJsonObject().has("verdict"));
        assertFalse(card.has("photos"));assertEquals(Verification.APPLIED,adapter.verify(p,submitted));
    }
    @Test void http200RequiresReadbackAndCannotDiscardOldCode()throws Exception {
        var p=preview();transport.apply=false;var submitted=adapter.submit(p);
        assertEquals(Verification.PENDING,adapter.verify(p,submitted));
        var codes=transport.card.getAsJsonArray("sizes").get(0).getAsJsonObject();var replaced=new JsonArray();replaced.add(GTIN);codes.add("skus",replaced);
        assertEquals(Verification.UNKNOWN,adapter.verify(p,submitted));
    }
    @Test void timeoutIsReconciledWithoutAnotherMutation()throws Exception {
        var repo=new GtinSyncRepository();var runner=new GtinSyncCoordinator(repo,k->adapter,Clock.systemUTC(),1,Duration.ZERO);
        var job=runner.confirm(List.of(preview()));transport.loseResponse=true;
        runner.runPending(1,Marketplace.WILDBERRIES);repo.recoverInterrupted();runner.runPending(1,Marketplace.WILDBERRIES);
        assertEquals(1,transport.writes.get());assertEquals(Status.SUCCEEDED,repo.jobItems(job).getFirst().status());
    }
    @Test void changedUntouchedFieldBlocksOverwrite()throws Exception {
        var p=preview();transport.card.addProperty("description","Changed outside app");
        assertThrows(GtinKnownUnsentException.class,()->adapter.submit(p));assertEquals(0,transport.writes.get());
    }
    @Test void nativeCharacteristicReorderingDoesNotInvalidateAnOtherwiseUnchangedGtinPreview()throws Exception {
        var first=transport.card.getAsJsonArray("characteristics").get(0);var second=JsonParser.parseString("{\"id\":88,\"name\":\"Composition\",\"value\":[\"Cotton\",\"Polyester\"]}");
        transport.card.getAsJsonArray("characteristics").add(second);var p=preview();
        var reordered=new JsonArray();reordered.add(second);reordered.add(first);transport.card.add("characteristics",reordered);
        assertEquals(p.before().fingerprint(),adapter.read(key).fingerprint());var submitted=adapter.submit(p);
        assertEquals(1,transport.writes.get());assertEquals(Verification.APPLIED,adapter.verify(p,submitted));
        assertEquals(List.of("Cotton","Polyester"),transport.sent.get(0).getAsJsonObject().getAsJsonArray("characteristics").get(0).getAsJsonObject().getAsJsonArray("value").asList().stream().map(JsonElement::getAsString).toList());
    }
    @Test void sameGtinOnAnotherSizeIsAConflict()throws Exception {
        var foreign=transport.card.deepCopy();var codes=new JsonArray();codes.add("00000017");foreign.getAsJsonArray("sizes").get(1).getAsJsonObject().add("skus",codes);
        transport.conflict=foreign;
        assertTrue(adapter.conflicts(key,GTIN));assertThrows(IllegalArgumentException.class,this::preview);assertEquals(0,transport.writes.get());
    }
    @Test void oldUnscopedRegistryCacheCannotAuthorizePreview()throws Exception {
        new GtinSyncRepository().saveCatalog(1,Marketplace.WILDBERRIES,List.of(new RegisteredGtin(GTIN,"Fixture","","",true,true,"National Catalog",Instant.now())));
        assertEquals("unverified_catalog_gtin",assertThrows(IllegalArgumentException.class,this::preview).getMessage());assertEquals(0,transport.writes.get());
    }
    @Test void ownerChangedAfterTransportWaitIsKnownUnsentAndPaused()throws Exception {
        var repo=new GtinSyncRepository();var p=preview();
        var runner=new GtinSyncCoordinator(repo,k->adapter,Clock.systemUTC(),1,Duration.ZERO);var job=runner.confirm(List.of(p));
        transport.beforeSend=()->saveSettings(shop,"B".repeat(40),com.vncode.app.integration.znack.ZnackModels.PRODUCTION_TRUE_API);
        runner.runPending(1,Marketplace.WILDBERRIES);
        assertEquals(0,transport.writes.get());assertEquals(Status.QUEUED,repo.jobItems(job).getFirst().status());assertTrue(repo.paused(job));
    }
    @Test void incompleteFullCardAndForeignShopNeverWrite() {
        transport.card.remove("dimensions");assertThrows(IOException.class,()->adapter.read(key));
        assertThrows(IllegalArgumentException.class,()->adapter.read(new ProductKey(2,Marketplace.WILDBERRIES,"101","11")));assertEquals(0,transport.writes.get());
    }
    @Test void explicitRejectionIsBoundToReadbackInsteadOfResend()throws Exception {
        var p=preview();transport.apply=false;transport.reject=true;var submitted=adapter.submit(p);
        assertEquals(Verification.REJECTED,adapter.verify(p,submitted));assertEquals(1,transport.writes.get());
    }
    @Test void legacyOuterWhitespaceIsActionableBeforeAnyWriteAndIsNeverSilentlyRenamed()throws Exception {
        transport.card.addProperty("vendorCode","Fixture ");assertTrue(adapter.capability(key).add(),"Capability discovery must not perform product reads");
        assertEquals("Fixture",adapter.read(key).article());assertEquals("Fixture ",transport.card.get("vendorCode").getAsString());
        assertEquals("WB_ARTICLE_EDGE_WHITESPACE",assertThrows(IllegalArgumentException.class,this::preview).getMessage());assertEquals(0,transport.writes.get());
    }
    @Test void aFreshNativeProductErrorEndsTheAcknowledgedJobWithoutReplayingIt()throws Exception {
        var repo=new GtinSyncRepository();var runner=new GtinSyncCoordinator(repo,k->adapter,Clock.systemUTC(),1,Duration.ZERO);var job=runner.confirm(List.of(preview()));transport.apply=false;
        transport.errors=errorList("Fixture",Instant.now().plusSeconds(1));runner.runJob(job);repo.recoverInterrupted();runner.runJob(job);
        var item=repo.jobItems(job).getFirst();assertEquals(Status.FAILED,item.status());assertEquals("WB_GTIN_CARD_REJECTED",repo.issue(item.id()));assertEquals(1,transport.writes.get());
    }
    @Test void staleOrAnotherArticlesErrorsCannotRejectTheCurrentGtinWrite()throws Exception {
        var p=preview();transport.apply=false;var sent=adapter.submit(p);
        transport.errors=errorList("Fixture",p.before().fetchedAt().minusSeconds(60));assertEquals(Verification.PENDING,adapter.verify(p,sent));
        transport.errors=errorList("Foreign",Instant.now().plusSeconds(1));assertEquals(Verification.PENDING,adapter.verify(p,sent));assertEquals(1,transport.writes.get());
    }
    private static JsonElement errorList(String article,Instant at){var item=new JsonObject();var codes=new JsonArray();codes.add(article);item.add("vendorCodes",codes);item.addProperty("updatedAt",at.toString());var errors=new JsonObject();var messages=new JsonArray();messages.add("Invalid fixture attributes");errors.add(article,messages);item.add("errors",errors);var items=new JsonArray();items.add(item);var data=new JsonObject();data.add("items",items);var root=new JsonObject();root.add("data",data);return root;}
    static final class Fixture implements ListingTransport {
        JsonObject card=JsonParser.parseString("""
          {"nmID":101,"subjectID":77,"vendorCode":"Fixture","title":"Keep title","description":"Keep description","brand":"Keep brand","kizMarked":true,
          "dimensions":{"length":12,"width":13,"height":14,"weightBrutto":0.123,"isValid":true},
          "characteristics":[{"id":99,"name":"Color","value":["Black"]}],
          "sizes":[{"chrtID":11,"techSize":"XL","wbSize":"50","skus":["legacy"]},{"chrtID":12,"techSize":"L","wbSize":"48","skus":["other-size"]}],
          "documents":{"items":[{"id":"owned-document","type":2,"number":"D","applicant":"Fixture","verdict":{"verified":true,"status":1}}],"overallVerdict":{"verified":true}},"photos":[{"big":"https://example.invalid/fixture.jpg"}]}
          """).getAsJsonObject();
        JsonObject conflict;JsonArray sent;JsonElement errors=JsonParser.parseString("{\"data\":{\"items\":[],\"cursor\":{\"next\":false}}}");AtomicInteger writes=new AtomicInteger();boolean apply=true,loseResponse,reject;Runnable beforeSend=()->{};
        public JsonElement call(String path,JsonElement body,boolean mutation)throws IOException {
            if(mutation){assertEquals("content/v2/cards/update",path);writes.incrementAndGet();sent=body.getAsJsonArray().deepCopy();if(apply)card=sent.get(0).getAsJsonObject().deepCopy();if(loseResponse)throw new IOException("fixture timeout");return JsonParser.parseString(reject?"{\"error\":true}":"{\"error\":false}");}
            if(path.equals("content/v2/cards/error/list"))return errors.deepCopy();
            assertEquals("content/v2/get/cards/list",path);String search=body.getAsJsonObject().getAsJsonObject("settings").getAsJsonObject("filter").get("textSearch").getAsString();
            var cards=new JsonArray();if(search.equals("101"))cards.add(card.deepCopy());else if(conflict!=null)cards.add(conflict.deepCopy());var out=new JsonObject();out.add("cards",cards);return out;
        }
        public JsonElement call(String path,JsonElement body,boolean mutation,Runnable guard)throws IOException{beforeSend.run();guard.run();return call(path,body,mutation);}
    }
}
