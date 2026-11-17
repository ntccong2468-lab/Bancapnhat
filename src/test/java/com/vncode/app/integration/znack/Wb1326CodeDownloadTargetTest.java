package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Durable capped-consumption intent in real SQLite; no remote downloads. */
class Wb1326CodeDownloadTargetTest {
    @TempDir Path directory;
    ZnackRepository repository;
    long order;
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Synthetic download','fixture'),(2,'Other synthetic','fixture')");}
        repository=new ZnackRepository(new ShopContext(1,"Synthetic download"));
        repository.upsertProducts(List.of(new Product(Wb1326PurchasePersistenceTest.gtin(1),"Synthetic","6103100000","CONFORMITY_DECLARATION","FIXTURE","01.01.2026",null)));
        order=repository.createDraft(Wb1326PurchasePersistenceTest.gtin(1),5);
        repository.updateOrder(order,"fixture-remote","READY",OrderStatus.CODES_READY,null);
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @Test void cappedTargetSurvivesRestartAndClearingAllowsTheRemainingQuantity(){
        repository.beginCodeDownload(order,3);
        var restarted=new ZnackRepository(repository.shop());assertEquals(3,restarted.codeDownloadTarget(order).orElseThrow());
        assertEquals(ZnackKizCodeService.DOWNLOAD_OUTCOME_UNCERTAIN,restarted.findOrder(order).orElseThrow().errorMessage());
        restarted.insertCodes(order,Wb1326PurchasePersistenceTest.gtin(1),new DownloadedCodes(List.of("fixture-one","fixture-two","fixture-three"),"fixture-block"));
        restarted.clearCodeDownloadTarget(order);assertTrue(restarted.codeDownloadTarget(order).isEmpty());assertNull(restarted.findOrder(order).orElseThrow().errorMessage());
        restarted.beginCodeDownload(order,5);assertEquals(5,restarted.codeDownloadTarget(order).orElseThrow());
        assertEquals(3,restarted.findCodes(order).size());
    }
    @Test void unresolvedTargetAndLegacyUncertaintyCannotBeOverwritten(){
        repository.beginCodeDownload(order,3);
        assertThrows(IllegalStateException.class,()->repository.beginCodeDownload(order,4));assertEquals(3,repository.codeDownloadTarget(order).orElseThrow());
        repository.clearCodeDownloadTarget(order);
        repository.updateOrder(order,null,null,OrderStatus.CODES_READY,ZnackKizCodeService.DOWNLOAD_OUTCOME_UNCERTAIN);
        assertThrows(IllegalStateException.class,()->repository.beginCodeDownload(order,2));assertTrue(repository.codeDownloadTarget(order).isEmpty());
    }
    @Test void targetAndMarkerRollbackTogetherWhenTheOrderWriteFails() throws Exception {
        String previous=repository.findOrder(order).orElseThrow().errorMessage();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("CREATE TRIGGER reject_download_marker BEFORE UPDATE OF error_message ON kiz_orders BEGIN SELECT RAISE(ABORT,'synthetic marker failure'); END");}
        assertThrows(RuntimeException.class,()->repository.beginCodeDownload(order,3));
        assertTrue(repository.codeDownloadTarget(order).isEmpty());assertEquals(previous,repository.findOrder(order).orElseThrow().errorMessage());
    }
    @Test void clearingOnlyRemovesTheSentinelAndIsScopedToTheOwningShop(){
        repository.beginCodeDownload(order,3);var other=new ZnackRepository(new ShopContext(2,"Other synthetic"));
        assertTrue(other.codeDownloadTarget(order).isEmpty());other.clearCodeDownloadTarget(order);
        assertEquals(3,repository.codeDownloadTarget(order).orElseThrow());assertThrows(IllegalArgumentException.class,()->other.beginCodeDownload(order,2));
        repository.updateOrder(order,null,null,OrderStatus.CODES_READY,"KEEP_DIAGNOSTIC");repository.clearCodeDownloadTarget(order);
        assertEquals("KEEP_DIAGNOSTIC",repository.findOrder(order).orElseThrow().errorMessage());assertTrue(repository.codeDownloadTarget(order).isEmpty());
    }
    @Test void targetCannotExceedApprovalOrReconsumeAnAlreadyStoredAmount(){
        assertThrows(IllegalArgumentException.class,()->repository.beginCodeDownload(order,6));
        repository.insertCodes(order,Wb1326PurchasePersistenceTest.gtin(1),new DownloadedCodes(List.of("fixture-already-stored"),"fixture-block"));
        assertThrows(IllegalArgumentException.class,()->repository.beginCodeDownload(order,1));assertTrue(repository.codeDownloadTarget(order).isEmpty());
    }
}
