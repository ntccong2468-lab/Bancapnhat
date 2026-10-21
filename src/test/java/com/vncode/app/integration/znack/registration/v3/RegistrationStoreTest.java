package com.vncode.app.integration.znack.registration.v3;

import com.vncode.app.config.Database;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.integration.znack.registration.v3.RegistrationJob.*;

class RegistrationStoreTest {
    @TempDir Path temp;
    RegistrationStore store;
    Owner owner=new Owner(1,"owner-a","production","scope-a");
    Product product=new Product(101,11,1,"ART","Áo","Brand","Đen","M","46",List.of("4601234567893"),"",List.of());
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) { s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','fixture')"); }
        store=new RegistrationStore();
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    @Test void repeatedImportPreservesJobAndMigrationPreservesLegacyData() throws Exception {
        var first=store.create(owner,product);assertEquals(first.id(),store.create(owner,product).id());
        try(var c=Database.getConnection()){RegistrationStore.initialize(c);RegistrationStore.initialize(c);}
        assertEquals(1,store.list(owner).size());assertEquals("ART",store.get(owner,first.id()).product().vendorCode());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM shops")){assertEquals(1,r.getInt(1));}
    }
    @Test void compareAndSwapPreventsStaleReviewFromOverwritingNewDraft(){
        var job=store.create(owner,product);var next=store.save(job,job.next(RegistrationState.GTIN_RESOLVED,job.data().withGtin("04601234567893")));
        assertThrows(IllegalStateException.class,()->store.save(job,job.next(RegistrationState.GTIN_RESOLVED,job.data().withGtin("04601234567893"))));
        assertEquals(next.revision(),store.get(owner,job.id()).revision());assertEquals(2,store.events(owner,job.id()).size());
    }
    @Test void checkpointSurvivesRestartAndCannotReplayUnknownMutation(){
        var job=store.create(owner,product);var op=store.begin(job,Action.ALLOCATE_GTIN,"request-hash",RegistrationState.GTIN_ALLOCATING);
        var reopened=new RegistrationStore();assertEquals("SENDING",reopened.operations(owner,job.id()).getFirst().status());
        assertThrows(IllegalStateException.class,()->reopened.begin(job,Action.ALLOCATE_GTIN,"request-hash",RegistrationState.GTIN_ALLOCATING));
        var active=reopened.get(owner,job.id());var unknown=active.next(RegistrationState.REMOTE_RESULT_UNKNOWN,active.data().withDetail("RESULT_UNKNOWN"));
        reopened.finish(op,unknown,"UNKNOWN");
        assertThrows(IllegalStateException.class,()->reopened.begin(unknown,Action.ALLOCATE_GTIN,"request-hash",RegistrationState.GTIN_ALLOCATING));
        assertEquals(1,reopened.operations(owner,job.id()).size());
    }
    @Test void abandonedWorkerBecomesUnknownOnRestartInsteadOfBeingReplayed()throws Exception {
        var job=store.create(owner,product);store.begin(job,Action.ALLOCATE_GTIN,"allocation",RegistrationState.GTIN_ALLOCATING);
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("UPDATE znack_v3_remote_operations SET worker_id='999999999@2000-01-01T00:00:00Z'");}
        var reopened=new RegistrationStore();assertEquals(RegistrationState.REMOTE_RESULT_UNKNOWN,reopened.get(owner,job.id()).state());
        assertEquals("UNKNOWN",reopened.operations(owner,job.id()).getFirst().status());
    }
    @Test void twoVariantsCannotPersistTheSameGtin(){
        var a=store.create(owner,product);store.save(a,a.next(RegistrationState.GTIN_RESOLVED,a.data().withGtin("04601234567893")));
        var other=new Product(101,12,1,"ART","Áo","Brand","Đen","L","48",List.of(),"",List.of());
        var b=store.create(owner,other);
        assertThrows(IllegalStateException.class,()->store.save(b,b.next(RegistrationState.GTIN_RESOLVED,b.data().withGtin("04601234567893"))));
        assertEquals(RegistrationState.PRODUCT_IMPORTED,store.get(owner,b.id()).state());
    }
    @Test void anUnknownAllocationBlocksTheSharedPoolUntilReconciled(){
        var first=store.create(owner,product);var op=store.begin(first,Action.ALLOCATE_GTIN,"first",RegistrationState.GTIN_ALLOCATING);
        var sending=store.get(owner,first.id());store.finish(op,sending.next(RegistrationState.REMOTE_RESULT_UNKNOWN,sending.data()),"UNKNOWN");
        var second=store.create(owner,new Product(102,12,1,"OTHER","Áo","Brand","Đen","L","48",List.of(),"",List.of()));
        assertThrows(IllegalStateException.class,()->store.begin(second,Action.ALLOCATE_GTIN,"second",RegistrationState.GTIN_ALLOCATING));
    }
    @Test void operationRetainsTheAcknowledgedFeedEvenIfALaterCorrectionClearsIt()throws Exception {
        var job=store.create(owner,product);job=store.save(job,job.next(RegistrationState.GTIN_RESOLVED,job.data().withGtin("04601234567893")));
        job=store.save(job,job.next(RegistrationState.ATTRIBUTES_READY,job.data()));job=store.save(job,job.next(RegistrationState.VALIDATED,job.data()));
        job=store.save(job,job.next(RegistrationState.READY_FOR_SUBMISSION,job.data()));job=store.save(job,job.next(RegistrationState.SUBMISSION_PENDING_CONFIRMATION,job.data()));
        var op=store.begin(job,Action.SUBMIT,"body",RegistrationState.SUBMITTING);var sending=store.get(owner,job.id());
        store.finish(op,sending.next(RegistrationState.SUBMITTED,sending.data().withRemote(7L,"feed-42","")),"ACKNOWLEDGED");
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT json_extract(checkpoint_json,'$.data.feedId') FROM znack_v3_remote_operations WHERE id=?")){
            p.setString(1,op.id());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals("feed-42",r.getString(1));}
        }
    }
    @Test void ownerAndProfileCannotReadAnotherJobsEvidence(){
        var job=store.create(owner,product);var other=new Owner(1,"owner-b","production","scope-a");
        assertTrue(store.list(other).isEmpty());assertThrows(SecurityException.class,()->store.get(other,job.id()));
        assertThrows(SecurityException.class,()->store.events(other,job.id()));
    }
}
