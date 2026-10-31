package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic Catalog reads exercise the exact persisted manual-repair image proof. */
class CatalogRepairImageBoundaryTest {
    @TempDir Path root;
    private OzonCardRegistrationWorkflowTest fixture;
    private RegistrationIntent intent;
    private ZnackCardRegistrationWorkflow.CatalogSession session;
    private FakeApi catalog;
    private JsonObject payload;
    private CatalogRepairImageEvidence.Images images;
    private final String gtin=GtinWriteTestFixture.GTIN;
    private final long goodId=91;

    @BeforeEach void setup() throws Exception {
        fixture=new OzonCardRegistrationWorkflowTest();fixture.root=root;fixture.setup();
        var scope=fixture.session().scope();
        assertTrue(fixture.repository.claim(fixture.shop,fixture.key,"9001","",scope,false));
        var raw=card("https://example.test/a.jpg");images=CatalogRepairImageEvidence.fromCatalog(raw,gtin,goodId);
        payload=ZnackNationalCatalogService.buildPayload(gtin,fixture.draft,"");payload.addProperty("good_id",goodId);payload.add("good_images",images.values());
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_card_registrations SET gtin=?,good_id=?,payload_json=?,status='READY_TO_SUBMIT',feed_id='' WHERE shop_id=? AND product_id=? AND offer_id=?")){
            p.setString(1,gtin);p.setLong(2,goodId);p.setString(3,payload.toString());p.setInt(4,fixture.shop.getId());p.setString(5,fixture.key.cardId());p.setString(6,fixture.key.variantId());assertEquals(1,p.executeUpdate());
        }
        String now=java.time.Instant.now().toString();
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO sync_runs(id,shop_id,marketplace,owner,kind,state,step,created_at,updated_at) VALUES('repair',?,'OZON',?,'catalog_repair','RUNNING','REPAIR',?,?)")){
            p.setInt(1,fixture.shop.getId());p.setString(2,ListingIdentity.fingerprint(fixture.shop));p.setString(3,now);p.setString(4,now);p.executeUpdate();
        }
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO sync_run_items(id,run_id,product_id,variant_id,gtin,state,step,updated_at) VALUES('item','repair',?,?,?,'RUNNING','REPAIR',?)")){
            p.setString(1,fixture.key.cardId());p.setString(2,fixture.key.variantId());p.setString(3,gtin);p.setString(4,now);p.executeUpdate();
        }
        var settings=new ZnackRepository(new ZnackModels.ShopContext(fixture.shop.getId(),fixture.shop.getName())).getSettings();
        catalog=new FakeApi(raw);var auth=new ZnackAuthService(catalog,null);
        var service=new ZnackNationalCatalogService(catalog,auth,(bytes,context)->{throw new AssertionError("no signing");},settings);
        session=new ZnackCardRegistrationWorkflow.CatalogSession("fixture",service,scope);
        intent=new RegistrationIntent("repair",0);
    }
    @AfterEach void cleanup(){fixture.clear();}

    private JsonObject card(String url){
        var result=new JsonObject();result.addProperty("good_id",goodId);result.addProperty("gtin",gtin);result.addProperty("good_status","draft");result.addProperty("owner_inn","7700000000");
        var states=new JsonArray();states.add("errors");result.add("good_detailed_status",states);
        var image=new JsonObject();image.addProperty("photo_type","default");image.addProperty("photo_url",url);
        var list=new JsonArray();list.add(image);result.add("good_images",list);return result;
    }
    private void store(){CatalogRepairImageEvidence.store(fixture.shop,fixture.key,session.scope(),intent,gtin,goodId,payload,images);}
    private void verify() throws Exception {CatalogRepairImageEvidence.verifyBeforeSubmit(fixture.shop,fixture.key,session.scope(),intent,gtin,goodId,payload,session);}
    @Test void sameFreshCatalogImageIsAcceptedButAChangedImageIsBlocked() throws Exception {
        store();verify();assertEquals(2,catalog.reads);
        catalog.card=card("https://example.test/changed.jpg");
        assertThrows(RegistrationValidationException.class,this::verify);assertEquals(3,catalog.reads);
    }
    @Test void missingProofAndChangedPayloadAreBlockedBeforeRemoteRead() throws Exception {
        assertThrows(RegistrationValidationException.class,this::verify);assertEquals(0,catalog.reads);
        var changed=payload.deepCopy();changed.getAsJsonArray("good_images").get(0).getAsJsonObject().addProperty("photo_url","https://example.test/other.jpg");
        assertThrows(RegistrationValidationException.class,()->CatalogRepairImageEvidence.store(fixture.shop,fixture.key,session.scope(),intent,gtin,goodId,changed,images));
        store();payload=changed;assertThrows(RegistrationValidationException.class,this::verify);assertEquals(0,catalog.reads);
    }
    @Test void changedOwnerAndReadFailureCannotAuthorizeThePost() throws Exception {
        store();catalog.fail=true;var rateLimit=assertThrows(ZnackApiClient.ZnackApiException.class,this::verify);assertEquals(429,rateLimit.statusCode());
        catalog.fail=false;
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key='different' WHERE id=?")){p.setInt(1,fixture.shop.getId());p.executeUpdate();}
        assertThrows(RuntimeException.class,this::verify);
    }
    @Test void cancelledRepairCannotReuseAStoredProof() throws Exception {
        store();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE sync_runs SET state='CANCELLED' WHERE id='repair'");}
        assertThrows(RegistrationValidationException.class,this::verify);assertEquals(0,catalog.reads);
    }
    @Test void ownerChangingBetweenOwnerProofAndImageReadCannotAuthorizeThePost() throws Exception {
        store();catalog.afterFirst=card("https://example.test/a.jpg");catalog.afterFirst.addProperty("owner_inn","7700000001");
        assertThrows(RuntimeException.class,this::verify);
    }

    private static final class FakeApi extends ZnackApiClient {
        JsonObject card,afterFirst;boolean fail;int reads;
        FakeApi(JsonObject card){this.card=card;}
        @Override public JsonElement productCards(String base,String token,String gtins)throws java.io.IOException {
            reads++;if(fail)throw new ZnackApiClient.ZnackApiException("fixture",429,"{}","GET","https://fixture.invalid",java.time.Duration.ofSeconds(15));var cards=new JsonArray();cards.add((reads>1&&afterFirst!=null?afterFirst:card).deepCopy());var result=new JsonObject();result.add("result",cards);return result;
        }
    }
}
