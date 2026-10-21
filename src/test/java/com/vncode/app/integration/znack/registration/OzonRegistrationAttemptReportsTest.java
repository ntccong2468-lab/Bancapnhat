package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.features.sync.SyncRunRepository;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OzonRegistrationAttemptReportsTest {
    @TempDir Path directory;
    Shop shop;
    final ProductKey key=new ProductKey(2,Marketplace.OZON,"501","fixture-offer");
    final SyncRunRepository reports=new SyncRunRepository();
    final OzonRegistrationBatchRepository batches=new OzonRegistrationBatchRepository();
    String run;

    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        run=OzonRegistrationAttemptReports.queue(shop,List.of(key));
        var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        batches.create(shop,List.of(key),false,ZnackRegistrationScope.fromSettings(settings),run);
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_card_registrations(shop_id,product_id,offer_id,sku,status,gtin,feed_id,good_id,scope_json,owner,created_at,updated_at) VALUES(2,'501','fixture-offer','9001','PUBLISHED',?,'feed-1',91,'{}',?,?,?)")){
            p.setString(1,GtinWriteTestFixture.GTIN);p.setString(2,ListingIdentity.fingerprint(shop));
            p.setString(3,Instant.now().toString());p.setString(4,Instant.now().toString());p.executeUpdate();
        }
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    SyncRunRepository.Item item(){return reports.items(run,0,10).getFirst();}
    void observe(String state,String detail){
        batches.item(new OzonRegistrationBatchRepository.Item(run,0,key,"RUNNING",""),state,detail);
        OzonRegistrationAttemptReports.observe(shop,run,key,state,detail);
    }

    @Test void pendingReadbackCannotReuseAnOlderPublishedNativeSnapshot(){
        observe("WAITING_REMOTE","CATALOG_READBACK_RETRY_PENDING");
        assertEquals("WAITING_REMOTE",item().state());
        assertFalse(JsonParser.parseString(item().evidence()).getAsJsonObject().get("publicationVerified").getAsBoolean());
        OzonRegistrationAttemptReports.refreshAll(shop,reports.find(run).orElseThrow());
        assertEquals("WAITING_REMOTE",item().state());assertEquals("WAITING_REMOTE",reports.find(run).orElseThrow().state());

        observe("SUBMITTED","");
        assertEquals("SUCCEEDED",item().state());
        assertTrue(JsonParser.parseString(item().evidence()).getAsJsonObject().get("publicationVerified").getAsBoolean());
    }

    @Test void failedReadbackRemainsActionRequiredWhenReopeningTheReport(){
        observe("FAILED","ZNACK_UNAUTHORIZED");
        assertEquals("NEEDS_ACTION",item().state());
        assertFalse(JsonParser.parseString(item().evidence()).getAsJsonObject().get("publicationVerified").getAsBoolean());
        OzonRegistrationAttemptReports.refreshAll(shop,reports.find(run).orElseThrow());
        assertEquals("NEEDS_ACTION",item().state());assertEquals("PARTIAL",reports.find(run).orElseThrow().state());
    }
}
