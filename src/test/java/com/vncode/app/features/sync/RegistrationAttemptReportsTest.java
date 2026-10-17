package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationRepository;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationAttemptReportsTest {
    @TempDir Path directory;
    final Shop shop=new Shop(1,"WB","fixture");
    final SyncRunRepository reports=new SyncRunRepository();
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','fixture')");
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,101,'PANTS','Fixture','fixture')");
            s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,11,101,'XL')");
        }
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    Sku sku(){return new ZnackCardRegistrationRepository().search(new SearchCriteria(1,"",List.of(),"ALL",20,0)).getFirst();}
    @Test void missingInputPersistsAnActualRegistrationFailureSeparateFromSourceReads(){
        var attempt=new RegistrationAttemptReports(shop,List.of(sku())).begin(sku());
        attempt.blocked("ZNACK_MISSING_PRODUCT_DOCUMENT");
        var run=reports.find(attempt.run()).orElseThrow();var item=reports.items(run.id(),0,10).getFirst();
        assertEquals(RegistrationAttemptReports.KIND,run.kind());assertEquals("PARTIAL",run.state());
        assertEquals("NEEDS_ACTION",item.state());assertEquals("ZNACK_MISSING_PRODUCT_DOCUMENT",item.error());assertEquals("",item.gtin());
        assertFalse(item.evidence().contains("fixture"));
    }
    @Test void acceptedRequestStaysPendingThenReportFollowsExactSavedCardAfterRestart()throws Exception{
        var original=sku();var attempt=new RegistrationAttemptReports(shop).begin(original);
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_card_registrations(shop_id,nm_id,chrt_id,gtin,feed_id,good_id,status,created_at,updated_at) VALUES(1,101,11,'04631993764370','feed-1',91,'FEED_SUBMITTED',?,?)")){
            p.setString(1,Instant.now().toString());p.setString(2,Instant.now().toString());p.executeUpdate();
        }
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_card_registration_scopes(shop_id,chrt_id,participant_inn,environment,fingerprint,created_at,owner_hash) VALUES(1,11,'7700000000','fixture','fixture',?,?)")){
            p.setString(1,Instant.now().toString());p.setString(2,ListingIdentity.fingerprint(shop));p.executeUpdate();
        }
        attempt.observe(Status.FEED_SUBMITTED,"");var run=reports.find(attempt.run()).orElseThrow();
        assertEquals("WAITING_REMOTE",run.state());var item=reports.items(run.id(),0,10).getFirst();
        assertEquals("04631993764370",item.gtin());assertTrue(item.evidence().contains("feed-1"));assertTrue(item.evidence().contains("91"));
        new ZnackCardRegistrationRepository().updateProgress(1,11,Status.PUBLISHED,"feed-1",91L,null,null);
        RegistrationAttemptReports.refresh(shop,run,List.of(item));
        assertEquals("SUCCEEDED",reports.find(run.id()).orElseThrow().state());assertEquals("CARD_PUBLISHED",reports.items(run.id(),0,10).getFirst().step());
        var otherOwner=new Shop(1,"WB","different-fixture");
        assertThrows(SecurityException.class,()->RegistrationAttemptReports.refresh(otherOwner,run,List.of(item)));
    }
    @Test void unpreparedProductsCannotBeCountedAsSuccessful(){
        var row=sku();var other=new Sku(102,12,1,"Other","","","Other","","L",List.of(),"",false,"",null,"",Status.NOT_CREATED,"",false,"");
        var attempt=new RegistrationAttemptReports(shop,List.of(row,other)).begin(row);attempt.blocked("ZNACK_REQUIRED_ATTRIBUTES_MISSING");
        var run=reports.find(attempt.run()).orElseThrow();assertEquals(2,run.total());assertEquals("RUNNING",run.state());
        assertEquals(1L,reports.counts(run.id()).get("QUEUED"));assertEquals(1L,reports.counts(run.id()).get("NEEDS_ACTION"));
    }
}
