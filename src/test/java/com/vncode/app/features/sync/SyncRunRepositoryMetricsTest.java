package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SyncRunRepositoryMetricsTest {
    @TempDir Path directory;
    @BeforeEach void initialize(){System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();}
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    @Test void sellerRunsRemainVisibleAfterHundredsOfBackgroundAndForeignOwnerRuns()throws Exception{
        var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);var repository=new SyncRunRepository();
        var registration=repository.begin(shop,RegistrationAttemptReports.KIND,"REGISTER");
        for(int i=0;i<105;i++)repository.begin(shop,"znack_catalog_read","READING");
        var foreign=repository.begin(shop,RegistrationAttemptReports.KIND,"REGISTER");
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE sync_runs SET owner='previous-account' WHERE id=?")){p.setString(1,foreign);p.executeUpdate();}
        assertEquals(java.util.List.of(registration),repository.listOwned(shop,java.util.Set.of(RegistrationAttemptReports.KIND),0,100).stream().map(SyncRunRepository.Run::id).toList());
        assertTrue(repository.listOwned(shop,java.util.Set.of(RegistrationAttemptReports.KIND),1,100).isEmpty());
        assertEquals(100,repository.listOwned(shop,java.util.Set.of("znack_catalog_read"),0,100).size());
        assertEquals(107,repository.list(shop.getId(),0,1000).size(),"Background audit records remain intact");
    }
    @Test void countersSeparatePlannedSentPendingFailedUnknownAndActualAppliedEvidence()throws Exception{
        var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);var repository=new SyncRunRepository();var run=repository.begin(shop,"znack_marketplace","MATCHING");String gtin=GtinWriteTestFixture.GTIN;
        var queued=repository.add(shop,run,"1","1","","","","",gtin,"AWAITING_CONFIRMATION","","{}");repository.update(shop,queued,"WAITING_REMOTE","READBACK","","confirmed-parent-only");
        repository.add(shop,run,"2","2","","","","",gtin,"WAITING_REMOTE","READBACK_PENDING","{\"marketplaceSubmitted\":true,\"syncResult\":\"PENDING\"}");
        repository.add(shop,run,"3","3","","","","",gtin,"FAILED","WB_HTTP_400","{\"marketplaceSubmitted\":true,\"syncResult\":\"FAILED\"}");
        repository.add(shop,run,"4","4","","","","",gtin,"RECONCILING","NETWORK_TIMEOUT","{\"marketplaceSubmitted\":true,\"syncResult\":\"UNKNOWN\"}");
        repository.add(shop,run,"5","5","","","","",gtin,"NEEDS_ACTION","GTIN_LOCAL_ACTIVATION_FAILED","{\"marketplaceVerified\":true}");
        repository.add(shop,run,"6","6","","","","","","NEEDS_ACTION","ZNACK_MAPPING_UNVERIFIED","{\"checkResult\":\"NO_MATCH\"}");
        repository.add(shop,run,"7","7","","","","","","NEEDS_ACTION","PRODUCT_NOT_FOUND","{\"checkResult\":\"UNKNOWN\"}");
        repository.add(shop,run,"8","8","","","","","","SUCCEEDED","","{broken legacy JSON");
        repository.add(shop,run,"9","9","","","","",gtin,"NEEDS_ACTION","OZON_BARCODE_BAD_CODE","{\"marketplaceSubmitted\":true,\"syncResult\":\"FAILED\"}");
        repository.add(shop,run,"catalog:unlinked","","","","","",gtin,"FAILED","CATALOG_CARD_READ_FAILED","{}");
        var counts=repository.marketplaceCounts(run);assertEquals(9L,counts.get("TOTAL"));assertEquals(6L,counts.get("WITH_GTIN"));assertEquals(2L,counts.get("WITHOUT_GTIN"));assertEquals(5L,counts.get("SUBMITTED"));assertEquals(1L,counts.get("UPDATED"));assertEquals(5L,counts.get("NOT_UPDATED"));assertEquals(2L,counts.get("PENDING"));assertEquals(2L,counts.get("FAILED"));assertEquals(2L,counts.get("UNVERIFIED"));
        assertEquals(2L,counts.get("BLOCKED"),"Verified marketplace updates and rows without GTIN are not blocked marketplace updates");
    }
    @Test void duplicateAddAndAllReadPathsReturnPersistedTimestampAndOriginalFacts()throws Exception{
        var shop=GtinWriteTestFixture.shop(Marketplace.OZON);var repository=new SyncRunRepository();var run=repository.begin(shop,"znack_marketplace","MATCHING");
        var original=repository.add(shop,run,"1","offer","sku","original","Blue","52",GtinWriteTestFixture.GTIN,"AWAITING_CONFIRMATION","","{}");assertFalse(original.updatedAt().isBlank());
        var duplicate=repository.add(shop,run,"1","offer","other","changed","Red","50","","FAILED","PRODUCT_CHANGED","{}");assertEquals(original,duplicate);
        assertEquals(original,repository.items(run,0,1000).getFirst());assertEquals(original,repository.item(run,original.id()).orElseThrow());assertEquals(original,repository.selectedItems(run,java.util.List.of(original.id())).get(original.id()));
        repository.update(shop,original,"WAITING_REMOTE","READBACK","READBACK_PENDING","parent");var current=repository.item(run,original.id()).orElseThrow();assertFalse(java.time.Instant.parse(current.updatedAt()).isBefore(java.time.Instant.parse(original.updatedAt())));assertEquals(current,repository.childItems(shop,run,"parent").getFirst());
    }
}
