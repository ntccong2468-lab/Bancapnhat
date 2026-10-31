package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CatalogReplacementEvidenceTest {
    @TempDir Path root; Shop shop;ProductKey key;ZnackRegistrationScope scope;RegistrationIntent intent;Fixture api;
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());key=new ProductKey(shop.getId(),Marketplace.WILDBERRIES,"101","201");
        var snapshot=new ProductSnapshot(key,"r1","Name","","Brand","article","1",List.of(),List.of(),List.of(new ProductVariant("201","article","Blue","L","",false)),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));
        var draft=new ZnackCardRegistrationModels.Draft("6204",1,"Name","Brand",Map.of());var schema=List.<ZnackCardRegistrationModels.Attribute>of();
        RegistrationFieldProvenance.save(shop,List.of(RegistrationFieldProvenance.capture(snapshot,snapshot,draft,schema,Map.of(),null,false)));
        var approved=RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),List.of(new RegistrationDynamicPreflight.Plan(snapshot,draft,schema)),Map.of(key,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.REPLACE,GtinWriteTestFixture.GTIN,91L)),Map.of(),Map.of(key,RegistrationFieldProvenance.read(shop,snapshot,draft,schema)),false);
        SqliteWriteTransaction.run(c->{RegistrationSourceConsent.persist(c,shop,"replacement-proof",List.of(key),approved);return null;});intent=new RegistrationIntent("replacement-proof",0);
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,good_id,feed_id,status,created_at,updated_at) VALUES(1,201,101,?,91,'old-feed','PUBLISHED','2026','2026')")){p.setString(1,GtinWriteTestFixture.GTIN);p.executeUpdate();}
        api=new Fixture();
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    private ZnackCardRegistrationWorkflow.CatalogSession session(){var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();return new ZnackCardRegistrationWorkflow.CatalogSession("fixture",new ZnackNationalCatalogService(api,null,null,settings),scope);}
    @Test void exactOwnerAndTerminalFeedAreRecheckedBeforeAndAfterArchiving()throws Exception{
        var proof=CatalogReplacementEvidence.verify(shop,key,scope,intent,session());assertEquals(1,api.feedReads);assertEquals(1,api.cardReads);
        try(var c=Database.getConnection()){assertDoesNotThrow(()->proof.assertCurrent(c,shop,key,intent));}
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.executeUpdate("INSERT INTO znack_card_registration_replacements(shop_id,chrt_id,active_gtin,active_good_id,active_feed_id,active_payload_json,active_wb_updated,state,created_at,updated_at) SELECT shop_id,chrt_id,gtin,good_id,feed_id,'',0,'CANDIDATE','2026','2026' FROM znack_card_registrations WHERE chrt_id=201");
            s.executeUpdate("UPDATE znack_card_registrations SET gtin=NULL,good_id=NULL,feed_id=NULL,status='CHECKING' WHERE chrt_id=201");
        }
        var archived=CatalogReplacementEvidence.verify(shop,key,scope,intent,session());try(var c=Database.getConnection()){assertDoesNotThrow(()->archived.assertCurrent(c,shop,key,intent));}
        try(var c=Database.getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE znack_card_registration_replacements SET active_feed_id='another-feed' WHERE chrt_id=201");}
        try(var c=Database.getConnection()){assertThrows(RegistrationValidationException.class,()->archived.assertCurrent(c,shop,key,intent));}
    }
    @Test void mismatchedOwnerOrPendingFeedNeverAuthorizesTheAllocation()throws Exception{
        api.owner="9999999999";assertThrows(RegistrationValidationException.class,()->CatalogReplacementEvidence.verify(shop,key,scope,intent,session()));
        api.owner="7700000000";api.status="Processing";assertThrows(RegistrationValidationException.class,()->CatalogReplacementEvidence.verify(shop,key,scope,intent,session()));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT gtin,good_id,feed_id,status FROM znack_card_registrations WHERE chrt_id=201")){assertTrue(r.next());assertEquals(GtinWriteTestFixture.GTIN,r.getString(1));assertEquals(91,r.getLong(2));assertEquals("old-feed",r.getString(3));assertEquals("PUBLISHED",r.getString(4));}
    }
    private static final class Fixture extends ZnackApiClient{
        String owner="7700000000",status="Completed";int feedReads,cardReads;
        @Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){feedReads++;return JsonParser.parseString("{\"result\":{\"feed_id\":\""+feed+"\",\"status\":\""+status+"\",\"item\":[{\"gtin\":\""+GtinWriteTestFixture.GTIN+"\",\"good_id\":91,\"status_code\":0}]}}");}
        @Override public JsonElement productCards(String base,String token,String gtin){cardReads++;return JsonParser.parseString("{\"result\":[{\"gtin\":\""+gtin+"\",\"good_id\":91,\"good_status\":\"published\",\"owner_inn\":\""+owner+"\"}]}");}
    }
}
