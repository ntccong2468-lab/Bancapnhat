package com.vncode.app.integration.znack.registration;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonSourceConsentPolicyTest {
    @TempDir Path root;Shop shop;ProductKey key=new ProductKey(2,Marketplace.OZON,"501","offer");String run,owner,source,scope,payload;
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        owner=ListingIdentity.fingerprint(shop);source=RegisteredGtinProof.source(shop);
        var s=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());scope=new Gson().toJson(s);
        run=new OzonRegistrationBatchRepository().create(shop,List.of(key),true,s);payload="{\"gtin\":\""+GtinWriteTestFixture.GTIN+"\",\"good_id\":91}";
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_card_registrations(shop_id,product_id,offer_id,sku,status,gtin,good_id,feed_id,payload_json,scope_json,owner,created_at,updated_at) VALUES(2,'501','offer','9001','READY_TO_SIGN',?,91,'feed',?,?,?,?,?)")){
            p.setString(1,GtinWriteTestFixture.GTIN);p.setString(2,payload);p.setString(3,scope);p.setString(4,owner);p.setString(5,Instant.now().toString());p.setString(6,Instant.now().toString());p.executeUpdate();
            try(var q=c.prepareStatement("UPDATE ozon_registration_batch_items SET feed_id='feed' WHERE run_id=?")){q.setString(1,run);q.executeUpdate();}
        }
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    void approved()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS znack_source_consents(run_id TEXT,position INTEGER,shop_id INTEGER,marketplace TEXT,product_id TEXT,variant_id TEXT,owner TEXT,scope TEXT,phase TEXT,expected_gtin_hash TEXT,submission_hash TEXT)");
            try(var p=c.prepareStatement("INSERT INTO znack_source_consents(run_id,position,shop_id,marketplace,product_id,variant_id,owner,scope,phase,expected_gtin_hash,submission_hash) VALUES(?,0,2,'OZON','501','offer',?,?,'SUBMITTED',?,?)")){
                p.setString(1,run);p.setString(2,owner);p.setString(3,source);p.setString(4,ListingIdentity.hash(GtinWriteTestFixture.GTIN));p.setString(5,ListingIdentity.hash(ListingIdentity.canonical(JsonParser.parseString(payload))));p.executeUpdate();
            }
        }
    }
    @Test void legacyReceiptWithoutConsentAllowsNoAutomaticSigningOrGtin(){assertFalse(OzonSourceConsentPolicy.permitsAutomatic(shop,key,GtinWriteTestFixture.GTIN));}
    @Test void exactCurrentReviewedSubmissionAllowsAutomaticWork()throws Exception{approved();assertTrue(OzonSourceConsentPolicy.permitsAutomatic(shop,key,GtinWriteTestFixture.GTIN));}
    @Test void changedFeedPayloadSourceOrConsentPhaseBlocksAutomaticWork()throws Exception{
        approved();try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("UPDATE ozon_card_registrations SET feed_id='other'");assertFalse(OzonSourceConsentPolicy.permitsAutomatic(shop,key,GtinWriteTestFixture.GTIN));
            s.execute("UPDATE ozon_card_registrations SET feed_id='feed',payload_json='{}'");assertFalse(OzonSourceConsentPolicy.permitsAutomatic(shop,key,GtinWriteTestFixture.GTIN));
            try(var p=c.prepareStatement("UPDATE ozon_card_registrations SET payload_json=?")){p.setString(1,payload);p.executeUpdate();}
            s.execute("UPDATE znack_source_consents SET phase='CANCELLED'");assertFalse(OzonSourceConsentPolicy.permitsAutomatic(shop,key,GtinWriteTestFixture.GTIN));
        }
    }
}
