package com.vncode.app.integration.znack.registration;

import com.google.gson.Gson;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationBatchReopenFeedTest {
    @TempDir Path root;
    Shop wb,ozon;
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        wb=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);ozon=GtinWriteTestFixture.shop(Marketplace.OZON);
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @Test void wbChecksEachNewRejectedFeedOnceAcrossRepositoryRecreation() throws Exception {
        String run="wb-reopen";String now=Instant.now().toString();
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_registration_batches(id,shop_id,owner,scope,auto_sign,state,created_at,updated_at) VALUES(?,?,?,'{}',0,'NEEDS_ACTION',?,?)")){
            p.setString(1,run);p.setInt(2,wb.getId());p.setString(3,ListingIdentity.fingerprint(wb));p.setString(4,now);p.setString(5,now);p.executeUpdate();
        }
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_registration_batch_items(run_id,position,sku_json,state,feed_id,updated_at) VALUES(?,0,'{}','NEEDS_ACTION','feed-1',?)")){
            p.setString(1,run);p.setString(2,now);p.executeUpdate();
        }
        assertTrue(new ZnackRegistrationBatchStore().reopenRejected(wb,run,List.of(0)));
        wbFailed(run,"feed-1");assertFalse(new ZnackRegistrationBatchStore().reopenRejected(wb,run,List.of(0)));
        wbFailed(run,"feed-2");assertTrue(new ZnackRegistrationBatchStore().reopenRejected(wb,run,List.of(0)));
        wbFailed(run,"feed-2");assertFalse(new ZnackRegistrationBatchStore().reopenRejected(wb,run,List.of(0)));
    }

    @Test void ozonChecksEachNewRejectedFeedOnceAcrossRepositoryRecreation() throws Exception {
        String run="ozon-reopen";String now=Instant.now().toString();
        var settings=new ZnackRepository(new ZnackModels.ShopContext(ozon.getId(),ozon.getName())).getSettings();
        var scope=ZnackRegistrationScope.fromSettings(settings);
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_registration_batches(run_id,shop_id,owner,scope_json,auto_sign,state,created_at,updated_at) VALUES(?,?,?,?,0,'FAILED',?,?)")){
            p.setString(1,run);p.setInt(2,ozon.getId());p.setString(3,ListingIdentity.fingerprint(ozon));p.setString(4,new Gson().toJson(scope));p.setString(5,now);p.setString(6,now);p.executeUpdate();
        }
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_registration_batch_items(run_id,position,product_id,offer_id,state,feed_id) VALUES(?,0,'501','offer','FAILED','feed-1')")){
            p.setString(1,run);p.executeUpdate();
        }
        var one=new OzonRegistrationBatchRepository();assertTrue(one.reopenRejected(ozon,one.latest(ozon),one.failedItems(one.latest(ozon))));
        ozonFailed(run,"feed-1");var two=new OzonRegistrationBatchRepository();assertFalse(two.reopenRejected(ozon,two.latest(ozon),two.failedItems(two.latest(ozon))));
        ozonFailed(run,"feed-2");var three=new OzonRegistrationBatchRepository();assertTrue(three.reopenRejected(ozon,three.latest(ozon),three.failedItems(three.latest(ozon))));
        ozonFailed(run,"feed-2");var four=new OzonRegistrationBatchRepository();assertFalse(four.reopenRejected(ozon,four.latest(ozon),four.failedItems(four.latest(ozon))));
    }

    @Test void legacyWbNewFeedIsReopenedOnlyWhenArchivedRejectionProvesItChanged() throws Exception {
        String run="wb-legacy";String now=Instant.now().toString();String old=Instant.now().minusSeconds(60).toString();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("ALTER TABLE znack_registration_batch_items DROP COLUMN correction_checked_feed");
            s.execute("INSERT INTO znack_registration_batches(id,shop_id,owner,scope,auto_sign,state,correction_resumed,created_at,updated_at) VALUES('"+run+"',1,'"+ListingIdentity.fingerprint(wb)+"','{}',0,'NEEDS_ACTION',1,'"+old+"','"+now+"')");
            s.execute("INSERT INTO znack_registration_batch_items(run_id,position,sku_json,state,detail,feed_id,updated_at) VALUES('"+run+"',0,'{\"chrtId\":11}','NEEDS_ACTION','prior rejection','feed-2','"+now+"')");
            s.execute("INSERT INTO znack_registration_batch_items(run_id,position,sku_json,state,detail,feed_id,updated_at) VALUES('"+run+"',1,'{\"chrtId\":12}','NEEDS_ACTION','unknown prior','feed-x','"+now+"')");
            s.execute("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,feed_id,status,created_at,updated_at) VALUES(1,11,101,'04601234567893','feed-2','ERROR','"+old+"','"+now+"')");
            s.execute("INSERT INTO znack_card_registration_history(shop_id,chrt_id,nm_id,gtin,feed_id,status,archived_at) VALUES(1,11,101,'04601234567893','feed-1','ERROR','"+now+"')");
            ZnackRegistrationBatchStore.initialize(c);
            try(var r=s.executeQuery("SELECT correction_checked_feed,detail FROM znack_registration_batch_items WHERE run_id='"+run+"' ORDER BY position")){
                assertTrue(r.next());assertEquals("",r.getString(1));
                assertTrue(r.next());assertEquals("feed-x",r.getString(1));assertTrue(r.getString(2).contains("CATALOG_LEGACY_REPAIR_REVIEW_REQUIRED"));
            }
        }
        var store=new ZnackRegistrationBatchStore();assertTrue(store.reopenRejected(wb,run,List.of(0,1)));
    }

    @Test void legacyOzonWithoutRejectionEvidenceStopsForReview() throws Exception {
        String now=Instant.now().toString();var scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(ozon.getId(),ozon.getName())).getSettings());
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("ALTER TABLE ozon_registration_batch_items DROP COLUMN correction_checked_feed");
            try(var p=c.prepareStatement("INSERT INTO ozon_registration_batches(run_id,shop_id,owner,scope_json,auto_sign,state,correction_resumed,created_at,updated_at) VALUES('ozon-legacy',?,?,?,0,'FAILED',1,?,?)")){
                p.setInt(1,ozon.getId());p.setString(2,ListingIdentity.fingerprint(ozon));p.setString(3,new Gson().toJson(scope));p.setString(4,now);p.setString(5,now);p.executeUpdate();
            }
            s.execute("INSERT INTO ozon_registration_batch_items(run_id,position,product_id,offer_id,state,detail,feed_id) VALUES('ozon-legacy',0,'501','offer','FAILED','prior rejection','feed-2')");
            OzonRegistrationBatchRepository.initialize(c);
            try(var r=s.executeQuery("SELECT correction_checked_feed,detail FROM ozon_registration_batch_items WHERE run_id='ozon-legacy'")){
                assertTrue(r.next());assertEquals("feed-2",r.getString(1));assertTrue(r.getString(2).contains("CATALOG_LEGACY_REPAIR_REVIEW_REQUIRED"));
            }
        }
        var repo=new OzonRegistrationBatchRepository();assertFalse(repo.reopenRejected(ozon,repo.latest(ozon),repo.failedItems(repo.latest(ozon))));
    }

    private void wbFailed(String run,String feed)throws Exception{
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_registration_batches SET state='NEEDS_ACTION' WHERE id=?")){p.setString(1,run);p.executeUpdate();}
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_registration_batch_items SET state='NEEDS_ACTION',feed_id=? WHERE run_id=?")){p.setString(1,feed);p.setString(2,run);p.executeUpdate();}
    }
    private void ozonFailed(String run,String feed)throws Exception{
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_registration_batches SET state='FAILED' WHERE run_id=?")){p.setString(1,run);p.executeUpdate();}
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_registration_batch_items SET state='FAILED',feed_id=? WHERE run_id=?")){p.setString(1,feed);p.setString(2,run);p.executeUpdate();}
    }
}
