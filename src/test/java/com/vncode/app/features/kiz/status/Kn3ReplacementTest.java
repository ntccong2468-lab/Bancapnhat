package com.vncode.app.features.kiz.status;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.kiz.KizService;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Kn3ReplacementTest {
    @TempDir Path directory;Shop shop;MockWebServer server;OzonApiClient api;final Kn3Repository repository=new Kn3Repository();
    static JsonObject posting(){return JsonParser.parseString("{\"result\":{\"posting_number\":\"101-1-1\",\"status\":\"awaiting_packaging\",\"products\":[{\"sku\":501,\"quantity\":2,\"offer_id\":\"fixture\",\"name\":\"Synthetic\"}],\"requirements\":{\"products_requiring_mandatory_mark\":[501]}}}").getAsJsonObject();}
    @BeforeEach void init(){System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();}
    @AfterEach void close()throws Exception{if(server!=null)server.shutdown();System.clearProperty("vncode.appdata.dir");}
    void ozon()throws Exception {
        shop=GtinWriteTestFixture.shop(Marketplace.OZON);new OzonPostingRepository().upsertDetail(shop.getId(),OzonJson.requirePostingDetail(posting(),"101-1-1"));server=new MockWebServer();server.start();
        api=new OzonApiClient(shop.getId(),new OzonCredentials(shop.getClientId(),shop.getApiKey()),server.url("/"),new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(3)).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1)),OzonApiClient.productionGuard(shop.getId(),new OzonCredentials(shop.getClientId(),shop.getApiKey())));
    }
    Kn3ReplacementService service(){return new Kn3ReplacementService((s,ids)->{throw new AssertionError("Not WB");},(s,rows)->{throw new AssertionError("Not WB");},s->api);}
    void response(JsonObject value){server.enqueue(new MockResponse().setBody(value.toString()).addHeader("Content-Type","application/json"));}
    JsonObject oldStatus(){return Kn3ProtocolTest.status("failed","[\"invalid_mark\"]");}
    @Test void acceptedWriteAcknowledgementStaysPendingUntilExactRemoteReadback()throws Exception {
        ozon();var status=oldStatus();var marks=OzonKn3Protocol.parse("101-1-1",status);repository.observe(shop,"101-1-1",marks);response(posting());response(status);response(new JsonObject());
        service().replace(shop,marks.getFirst(),"new");assertEquals(Kn3Mark.State.PENDING,Kn3Mark.summary(repository.read(shop,"101-1-1")));
        assertEquals("/v3/posting/fbs/get",server.takeRequest().getPath());assertEquals("/v5/fbs/posting/product/exemplar/status",server.takeRequest().getPath());var sent=server.takeRequest();assertEquals("/v6/fbs/posting/product/exemplar/set",sent.getPath());var payload=JsonParser.parseString(sent.getBody().readUtf8()).getAsJsonObject();assertEquals(2,payload.getAsJsonArray("products").get(0).getAsJsonObject().getAsJsonArray("exemplars").size());assertTrue(payload.toString().contains("keep-other"));
        var current=Kn3ProtocolTest.status("passed","[]");current.getAsJsonArray("products").get(0).getAsJsonObject().getAsJsonArray("exemplars").get(0).getAsJsonObject().getAsJsonArray("marks").get(0).getAsJsonObject().addProperty("mark","new");response(current);
        new Kn3SyncService().ozon(shop,OzonJson.requirePostingDetail(posting(),"101-1-1"),api);assertEquals(Kn3Mark.State.ACCEPTED,Kn3Mark.summary(repository.read(shop,"101-1-1")));assertEquals("DONE",phase());
    }
    @Test void anotherExemplarHavingTheNewCodeCannotConfirmTheTargetReplacement()throws Exception {
        ozon();var marks=OzonKn3Protocol.parse("101-1-1",oldStatus());repository.observe(shop,"101-1-1",marks);String id=repository.begin(shop,marks.getFirst(),"new","{}");repository.dispatch(shop,id,"AWAITING");
        var wrong=List.of(marks.getFirst(),new Kn3Mark("101-1-1","501","12","new",Kn3Mark.State.ACCEPTED,"passed"));repository.observe(shop,"101-1-1",wrong);
        assertEquals("AWAITING",phase());assertEquals(Kn3Mark.State.PENDING,Kn3Mark.summary(repository.read(shop,"101-1-1")));
    }
    @Test void aTimeoutOr503CannotReplayTheSetRequestAndRemainsReconciling()throws Exception {
        ozon();var status=oldStatus();var target=OzonKn3Protocol.parse("101-1-1",status).getFirst();repository.observe(shop,"101-1-1",OzonKn3Protocol.parse("101-1-1",status));response(posting());response(status);server.enqueue(new MockResponse().setResponseCode(503).setBody("{}"));
        assertThrows(java.io.IOException.class,()->service().replace(shop,target,"new"));assertEquals(3,server.getRequestCount());assertEquals("RECONCILE",phase());assertEquals(Kn3Mark.State.PENDING,Kn3Mark.summary(repository.read(shop,"101-1-1")));
        response(posting());response(status);assertThrows(IllegalStateException.class,()->service().replace(shop,target,"new"));assertEquals(5,server.getRequestCount());
    }
    @Test void knownRejectionKeepsRedAndAllowsASeparateNewAttempt()throws Exception {
        ozon();var status=oldStatus();var target=OzonKn3Protocol.parse("101-1-1",status).getFirst();repository.observe(shop,"101-1-1",OzonKn3Protocol.parse("101-1-1",status));response(posting());response(status);server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"code\":3,\"message\":\"invalid request\"}"));
        assertThrows(java.io.IOException.class,()->service().replace(shop,target,"new"));assertEquals("FAILED",phase());assertEquals(Kn3Mark.State.REJECTED,Kn3Mark.summary(repository.read(shop,"101-1-1")));
        response(posting());response(status);response(new JsonObject());service().replace(shop,target,"different-new");assertEquals("AWAITING",phase());
    }
    @Test void missingOneRequiredExemplarCannotShowGreen()throws Exception {
        ozon();var status=Kn3ProtocolTest.status("passed","[]");status.getAsJsonArray("products").get(0).getAsJsonObject().getAsJsonArray("exemplars").remove(1);response(status);
        new Kn3SyncService().ozon(shop,OzonJson.requirePostingDetail(posting(),"101-1-1"),api);assertEquals(Kn3Mark.State.PENDING,Kn3Mark.summary(repository.read(shop,"101-1-1")));
    }
    @Test void credentialRotationHidesOldVerdictsAndCannotModifyWithAnOldShop()throws Exception {
        ozon();repository.observe(shop,"101-1-1",OzonKn3Protocol.parse("101-1-1",oldStatus()));
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key=? WHERE id=?")){p.setString(1,"rotated-fixture");p.setInt(2,shop.getId());p.executeUpdate();}
        assertThrows(SecurityException.class,()->repository.read(shop,"101-1-1"));var changed=new Shop(shop.getId(),shop.getName(),Marketplace.OZON,shop.getClientId(),"rotated-fixture");assertTrue(repository.read(changed,"101-1-1").isEmpty());
    }
    @Test void wbQueueReceivesOnlyTheExactFreshlyRejectedMark()throws Exception {
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO wb_orders(shop_id,order_id,supplier_status,synced_at) VALUES(1,101,'confirm','now')");}
        var count=new AtomicInteger();var target=new Kn3Mark("101","","","old",Kn3Mark.State.REJECTED,"sgtinRetired");repository.observe(shop,"101",List.of(target));
        var worker=new Kn3ReplacementService((s,ids)->Map.of(101L,new KizService.SgtinMetadata(true,"old","sgtinRetired")),(s,rows)->{count.incrementAndGet();assertEquals(target.fingerprint(),rows.getFirst().expectedRejectedHash());assertTrue(rows.getFirst().replaceExisting());assertEquals("new",rows.getFirst().kizCode());},s->{throw new AssertionError("Not Ozon");});
        worker.replace(shop,target,"new");assertEquals(1,count.get());assertEquals(Kn3Mark.State.PENDING,Kn3Mark.summary(repository.read(shop,"101")));assertEquals("PREPARED",phase());
    }
    @Test void wbAcceptedOrChangedCodeIsNeverRemovedFromAStaleRedSelection()throws Exception {
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO wb_orders(shop_id,order_id,supplier_status,synced_at) VALUES(1,101,'confirm','now')");}
        var target=new Kn3Mark("101","","","old",Kn3Mark.State.REJECTED,"sgtinRetired");
        var worker=new Kn3ReplacementService((s,ids)->Map.of(101L,new KizService.SgtinMetadata(true,"old","sgtinIntroduced")),(s,rows)->fail("No write may be queued"),s->{throw new AssertionError("Not Ozon");});
        assertThrows(java.io.IOException.class,()->worker.replace(shop,target,"new"));assertEquals(0,replacementCount());
    }
    String phase()throws Exception{try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT state FROM kn3_replacements ORDER BY rowid DESC LIMIT 1")){assertTrue(r.next());return r.getString(1);}}
    int replacementCount()throws Exception{try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM kn3_replacements")){return r.getInt(1);}}
}
