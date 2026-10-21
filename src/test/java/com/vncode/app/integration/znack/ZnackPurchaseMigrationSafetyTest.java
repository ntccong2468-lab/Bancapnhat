package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ZnackPurchaseMigrationSafetyTest {
    private static final String GTIN="04601234567890",SHORT=GTIN.substring(1);
    @TempDir Path directory;ZnackRepository repository;
    record Saved(long id,String gtin,Long order,String key,String stage,String error,String created,String updated){}
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var st=c.createStatement()){st.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Fixture','fixture'),(2,'Other','other')");}
        repository=new ZnackRepository(new ShopContext(1,"Fixture"));repository.upsertProducts(List.of(new Product(GTIN,"Synthetic",null,null,null,null,null)));
    }
    @AfterEach void cleanup(){ZnackPurchaseCoordinator.cancelPendingPolls();ZnackSigningSession.resetForTests();System.clearProperty("vncode.appdata.dir");}
    private void legacyMode()throws Exception{
        try(var c=Database.getConnection();var st=c.createStatement()){
            st.execute("DROP INDEX IF EXISTS uq_znack_purchase_pipeline_active");st.execute("DROP TRIGGER IF EXISTS znack_purchase_claim_insert");st.execute("DROP TRIGGER IF EXISTS znack_purchase_claim_update");
            st.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(1,'"+SHORT+"','Legacy','now')");
        }
    }
    private long order(String external,String gtin)throws Exception{
        long id=repository.createDraft(GTIN,2);repository.updateOrder(id,external,external==null?null:"CREATED",external==null?OrderStatus.FAILED:OrderStatus.SUBMITTED,external==null?"response lost":null);
        if(gtin.equals(SHORT))try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE kiz_orders SET gtin=? WHERE id=?")){p.setString(1,SHORT);p.setLong(2,id);p.executeUpdate();}return id;
    }
    private void seed(long id,String gtin,Long order,String stage)throws Exception{
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_purchase_pipelines(id,shop_id,gtin,quantity,order_id,request_key,stage,error_message,created_at,updated_at) VALUES(?,1,?,2,?,?,?,?,'2026-01-01T00:00:00Z','2026-01-01T00:01:00Z')")){
            p.setLong(1,id);p.setString(2,gtin);if(order==null)p.setNull(3,Types.BIGINT);else p.setLong(3,order);p.setString(4,"request-"+id);p.setString(5,stage);p.setString(6,"preserved-"+stage);p.executeUpdate();
        }
    }
    private List<Saved> saved()throws Exception{
        var rows=new ArrayList<Saved>();try(var c=Database.getConnection();var st=c.createStatement();var r=st.executeQuery("SELECT * FROM znack_purchase_pipelines ORDER BY id")){
            while(r.next()){long order=r.getLong("order_id");Long identity=r.wasNull()?null:order;rows.add(new Saved(r.getLong("id"),r.getString("gtin"),identity,r.getString("request_key"),r.getString("stage"),r.getString("error_message"),r.getString("created_at"),r.getString("updated_at")));}
        }return rows;
    }
    @Test void canonicalCollisionPreservesEverySentAndUnknownReceiptCodesAndHistoryAcrossTwoInitializations()throws Exception{
        legacyMode();long accepted=order("accepted",GTIN),downloaded=order("downloaded",SHORT),uncertain=order(null,GTIN);
        repository.insertCodes(downloaded,GTIN,new DownloadedCodes(List.of("synthetic-preserved-code"),"block"));
        seed(11,GTIN,accepted,"POLLING_ORDER");seed(12,SHORT,downloaded,"DOWNLOADING_CODES");seed(13,GTIN,uncertain,"RECONCILING_ORDER");seed(14,SHORT,null,"CREATING_ORDER");seed(15,SHORT,accepted,"VALIDATING");
        var before=saved();Database.initDatabase();var first=saved();Database.initDatabase();assertEquals(first,saved());assertEquals(5,first.size());
        for(int index=0;index<before.size();index++){var old=before.get(index);var current=first.get(index);assertEquals(GTIN,current.gtin());assertEquals(old.order(),current.order());assertEquals(old.stage(),current.stage());assertEquals(old.key(),current.key());assertEquals(old.error(),current.error());assertEquals(old.created(),current.created());assertEquals(old.updated(),current.updated());}
        try(var c=Database.getConnection();var st=c.createStatement()){
            try(var codes=st.executeQuery("SELECT raw_code,order_id FROM kiz_codes")){assertTrue(codes.next());assertEquals("synthetic-preserved-code",codes.getString(1));assertEquals(downloaded,codes.getLong(2));assertFalse(codes.next());}
            try(var keys=st.executeQuery("PRAGMA foreign_key_check")){assertFalse(keys.next());}
        }
        assertTrue(repository.activateNextQueuedPipeline(GTIN).isEmpty());assertThrows(RuntimeException.class,()->repository.createPipeline(GTIN,2));
        long next=repository.enqueuePipeline(GTIN,2,"new-confirmed-request");assertEquals(PurchaseStage.QUEUED,repository.findPipeline(next).orElseThrow().stage());
        assertThrows(RuntimeException.class,()->repository.updatePipeline(next,null,PurchaseStage.VALIDATING,null));
        assertThrows(RuntimeException.class,()->repository.updatePipeline(next,null,PurchaseStage.CREATING_ORDER,null));
        repository.updatePipeline(13,uncertain,PurchaseStage.POLLING_ORDER,"recovered readback");assertEquals(PurchaseStage.POLLING_ORDER,repository.findPipeline(13).orElseThrow().stage());
    }
    @Test void sameGtinLegacyActiveDuplicatesAreNotResetJustToCreateAnIndex()throws Exception{
        legacyMode();seed(1,GTIN,order("one",GTIN),"POLLING_ORDER");seed(2,GTIN,order(null,GTIN),"RECONCILING_ORDER");
        var before=saved();Database.initDatabase();assertEquals(before,saved());Database.initDatabase();assertEquals(before,saved());
        assertThrows(RuntimeException.class,()->repository.createPipeline(GTIN,2));
    }
    @Test void onlyProvenPrePostDuplicateValidationCanReturnToFifoAndPaidClaimsStayExcluded()throws Exception{
        legacyMode();seed(1,GTIN,null,"VALIDATING");seed(2,SHORT,null,"VALIDATING");Database.initDatabase();
        assertEquals(PurchaseStage.VALIDATING,repository.findPipeline(1).orElseThrow().stage());assertEquals(PurchaseStage.QUEUED,repository.findPipeline(2).orElseThrow().stage());assertEquals("preserved-VALIDATING",repository.findPipeline(2).orElseThrow().errorMessage());
        repository.updatePipeline(1,null,PurchaseStage.CREATING_ORDER,null);assertThrows(RuntimeException.class,()->repository.updatePipeline(2,null,PurchaseStage.CREATING_ORDER,null));assertTrue(repository.activateNextQueuedPipeline(GTIN).isEmpty());
        repository.updatePipeline(1,null,PurchaseStage.FAILED,"explicit rejection before acceptance");assertEquals(2L,repository.activateNextQueuedPipeline(GTIN).orElseThrow().id());
    }
    @Test void canonicalUnknownAttemptQueuesEveryUnsentValidatorEvenWhenItsIdIsOlder()throws Exception{
        legacyMode();seed(1,SHORT,null,"VALIDATING");seed(2,GTIN,null,"CREATING_ORDER");Database.initDatabase();
        assertEquals(PurchaseStage.QUEUED,repository.findPipeline(1).orElseThrow().stage());assertEquals(PurchaseStage.CREATING_ORDER,repository.findPipeline(2).orElseThrow().stage());assertTrue(repository.activateNextQueuedPipeline(GTIN).isEmpty());
    }
}
