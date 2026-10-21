package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only the feed POST uses real HTTP, against the local fixture. No seller endpoint is called. */
class ZnackReviewedDraftSubmissionTest {
    @TempDir Path directory;private String prior;private Shop shop;private HttpServer server;
    private final AtomicInteger posts=new AtomicInteger();private final AtomicReference<JsonObject> wire=new AtomicReference<>();
    private JsonObject card;private int status=200;private ZnackModels.Settings settings;private ZnackApiClient api;
    @BeforeEach void setup()throws Exception{
        prior=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v3/feed",exchange->{
            posts.incrementAndGet();wire.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] body=(status==200?"{\"result\":{\"feed_id\":\"accepted-fixture\"}}":"{\"error\":\"fixture unavailable\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        GtinWriteTestFixture.saveSettings(shop,GtinWriteTestFixture.CERTIFICATE,"http://127.0.0.1:"+server.getAddress().getPort());
        settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        card=payload();card.addProperty("good_status","draft");card.add("good_detailed_status",JsonParser.parseString("[\"draft\"]"));card.addProperty("good_signed",false);
        api=new ZnackApiClient(){
            @Override public JsonElement productCards(String base,String token,String gtin){var root=new JsonObject();var rows=new JsonArray();rows.add(card.deepCopy());root.add("result",rows);return root;}
            @Override public JsonElement nationalCatalogAttributes(String base,String token,long category){return model();}
            @Override public JsonElement nationalCatalogAllAttributes(String base,String token,long category){return model();}
            private JsonElement model(){return JsonParser.parseString("{\"result\":[{\"attr_id\":1,\"attr_name\":\"Fixture\",\"attr_field_type\":\"text\"}]}");}
        };
    }
    @AfterEach void cleanup(){if(server!=null)server.stop(0);if(prior==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",prior);}
    @Test void reviewedDraftUsesTheSameIdentityAndNeverSendsLocalMetadata()throws Exception{
        var marked=ZnackNationalCatalogService.reviewedDraftPayload(shop,payload());var checkpoint=new AtomicInteger();
        assertEquals("accepted-fixture",service().submitReviewedDraft(shop,"fixture-token",marked,checkpoint::incrementAndGet));
        assertEquals(1,posts.get());assertEquals(1,checkpoint.get());assertEquals(91,wire.get().get("good_id").getAsLong());
        assertEquals(GtinWriteTestFixture.GTIN,wire.get().get("gtin").getAsString());assertEquals(1,wire.get().get("moderation").getAsInt());
        assertFalse(wire.get().has("_vncode_reviewed_draft"));assertTrue(marked.has("_vncode_reviewed_draft"),"The durable local review remains intact");
    }
    @Test void reopenedServiceAcceptsOnlyTheUnchangedFrozenReview()throws Exception{
        var persisted=ZnackNationalCatalogService.reviewedDraftPayload(shop,payload()).toString();
        assertEquals("accepted-fixture",service().submitReviewedDraft(shop,"fixture-token",JsonParser.parseString(persisted).getAsJsonObject(),()->{}));
        assertEquals(1,posts.get());
    }
    @Test void changedPayloadHashIdentityOrOwnerNeverDispatches(){
        for(String field:List.of("good_name","gtin","good_id")){
            var marked=ZnackNationalCatalogService.reviewedDraftPayload(shop,payload());marked.addProperty(field,field.equals("good_id")?"92":field.equals("gtin")?"00000000000024":"Changed");
            assertThrows(RegistrationValidationException.class,()->service().submitReviewedDraft(shop,"fixture-token",marked,()->fail("No dispatch checkpoint")));
        }
        var marked=ZnackNationalCatalogService.reviewedDraftPayload(shop,payload());marked.getAsJsonObject("_vncode_reviewed_draft").addProperty("owner","different-owner");
        assertThrows(ZnackRegistrationScope.ScopeChangedException.class,()->service().submitReviewedDraft(shop,"fixture-token",marked,()->fail("No dispatch checkpoint")));
        assertEquals(0,posts.get());
    }
    @Test void remotePublishedModerationNotSignedAndChangedIdentityCannotBeUpdated(){
        var original=card.deepCopy();
        for(String state:List.of("published","moderation","notsigned","unknown-stage")){
            card=original.deepCopy();card.add("good_detailed_status",JsonParser.parseString("[\""+state+"\"]"));if(state.equals("published")){card.addProperty("good_status","published");card.addProperty("good_signed",true);}
            var marked=ZnackNationalCatalogService.reviewedDraftPayload(shop,payload());
            assertThrows(Exception.class,()->service().submitReviewedDraft(shop,"fixture-token",marked,()->fail("No dispatch checkpoint")),state);
        }
        card=original.deepCopy();card.addProperty("good_id",92);
        assertThrows(Exception.class,()->service().submitReviewedDraft(shop,"fixture-token",ZnackNationalCatalogService.reviewedDraftPayload(shop,payload()),()->fail("No dispatch checkpoint")));
        card=original.deepCopy();card.addProperty("tnved","6103109000");
        assertThrows(RegistrationValidationException.class,()->service().submitReviewedDraft(shop,"fixture-token",ZnackNationalCatalogService.reviewedDraftPayload(shop,payload()),()->fail("No dispatch checkpoint")));
        assertEquals(0,posts.get());
    }
    @Test void ordinarySubmitCannotLeakOrBypassTheReviewedDraftGuard(){
        var marked=ZnackNationalCatalogService.reviewedDraftPayload(shop,payload());
        assertThrows(RegistrationValidationException.class,()->service().submit("fixture-token",marked,()->fail("No dispatch checkpoint")));
        assertEquals(0,posts.get());
    }
    @Test void failedNonIdempotentPostIsNeverReplayed(){
        status=503;var marked=ZnackNationalCatalogService.reviewedDraftPayload(shop,payload());var checkpoint=new AtomicInteger();
        assertThrows(Exception.class,()->service().submitReviewedDraft(shop,"fixture-token",marked,checkpoint::incrementAndGet));
        assertEquals(1,posts.get());assertEquals(1,checkpoint.get());assertFalse(wire.get().has("_vncode_reviewed_draft"));
    }
    private ZnackNationalCatalogService service(){return new ZnackNationalCatalogService(api,new ZnackAuthService(api,null),null,settings);}
    private static JsonObject payload(){var p=ZnackNationalCatalogService.buildPayload(GtinWriteTestFixture.GTIN,new ZnackCardRegistrationModels.Draft("6204420000",1,"Fixture","Brand",Map.of(1L,"Fixture")),"");p.addProperty("good_id",91);return p;}
}
