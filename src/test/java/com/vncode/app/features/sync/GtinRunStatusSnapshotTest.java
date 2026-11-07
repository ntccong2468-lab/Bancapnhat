package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class GtinRunStatusSnapshotTest {
    @TempDir Path directory;String prior;
    @BeforeEach void setup(){prior=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();}
    @AfterEach void cleanup(){if(prior==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",prior);}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void screenshotCountsDoNotCountMissingGtinsAsBlockedUpdates(Marketplace marketplace)throws Exception{
        var shop=GtinWriteTestFixture.shop(marketplace);var repo=new SyncRunRepository();String run=repo.begin(shop,"znack_marketplace","READBACK");
        var items=new java.util.ArrayList<SyncRunRepository.Item>();
        for(int i=0;i<1982;i++){
            boolean has=i<1430;String state=i<1141?"SUCCEEDED":i<1157?"WAITING_REMOTE":"NEEDS_ACTION";
            items.add(new SyncRunRepository.Item("fixture-"+i,run,"product-"+i,"variant-"+i,"","Article","Blue","XL",has?GtinWriteTestFixture.GTIN:"",state,"READBACK",has?"":"GTIN_MISSING","",has?"{}":"{\"checkResult\":\"NO_MATCH\"}"));
        }
        repo.addBatch(shop,run,items);
        var result=GtinRunStatusSnapshot.read(GtinRunStatusSnapshot.capture(shop,Set.of()),run).result();
        assertEquals(1982,result.inventory().total());assertEquals(1430,result.inventory().withGtin());assertEquals(552,result.inventory().withoutGtin());
        assertEquals(1141,result.progress().succeeded());assertEquals(16,result.progress().pending());
        assertEquals(273,result.progress().blocked(),"The 552 unmatched rows are a separate outcome, not 552 more blocked GTIN updates");
        assertEquals(273L,repo.marketplaceCounts(run).get("BLOCKED"),"The dialog and background status must use the same scope");
        assertEquals(result.inventory().total(),result.progress().succeeded()+result.progress().pending()+result.progress().blocked()+result.inventory().withoutGtin()+result.inventory().unknown());
    }
    @Test void durableReadbackPreservesSelectedScopeAndMarketplaceTruthDespiteLocalActivationFailure()throws Exception{
        var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);var repo=new SyncRunRepository();String run=repo.begin(shop,"znack_marketplace","READBACK");
        var first=repo.add(shop,run,"100","101","","","","",GtinWriteTestFixture.GTIN,"WAITING_REMOTE","","{}");
        repo.add(shop,run,"100","102","","","","","","NEEDS_ACTION","MISSING_GTIN","{}");
        repo.add(shop,run,"other","103","","","","",GtinWriteTestFixture.GTIN,"SUCCEEDED","","{}");
        repo.add(shop,run,"catalog:orphan","","","","","",GtinWriteTestFixture.GTIN,"NEEDS_ACTION","CATALOG_NOT_FOUND","{}");
        assertEquals(1L,repo.marketplaceCounts(run).get("UPDATED"),"The whole-run count already contains the verified, unselected size");
        var context=GtinRunStatusSnapshot.capture(shop,Set.of(new ProductKey(1,Marketplace.WILDBERRIES,"100","101"),new ProductKey(1,Marketplace.WILDBERRIES,"100","102")));
        var pending=GtinRunStatusSnapshot.read(context,run);assertFalse(pending.settled());assertEquals(1,pending.reasons().get("GTIN_READBACK_PENDING"));assertEquals(1,pending.reasons().get("MISSING_GTIN"));assertEquals(1,pending.result().progress().pending());assertEquals(2,pending.result().inventory().total());assertEquals(1,pending.result().inventory().withoutGtin());
        repo.registrationProgress(shop,first,first.gtin(),"NEEDS_ACTION","LOCAL_ACTIVATION","GTIN_LOCAL_ACTIVATION_FAILED","{\"marketplaceVerified\":true}");
        // A later outer worker failure updates state only; previously persisted marketplace truth survives.
        repo.update(shop,repo.item(run,first.id()).orElseThrow(),"NEEDS_ACTION","READBACK","SYNC_STORAGE",null);
        assertEquals(2L,repo.marketplaceCounts(run).get("UPDATED"),"Whole-run count includes both the newly verified selected size and the already verified unselected size");
        var verified=GtinRunStatusSnapshot.read(context,run);assertTrue(verified.settled());assertEquals(1,verified.result().progress().succeeded());assertEquals(0,verified.result().progress().pending());assertEquals(0,verified.result().progress().blocked(),"The marketplace truth is complete; local activation failure stays in the detailed report");assertEquals(1,verified.result().inventory().withGtin());
    }
    @Test void verifiedMarketplaceReadbackSettlesEvenWhileLocalActivationIsPending()throws Exception{
        var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);var repo=new SyncRunRepository();String run=repo.begin(shop,"znack_marketplace","READBACK");
        var item=repo.add(shop,run,"100","101","","","","",GtinWriteTestFixture.GTIN,"WAITING_REMOTE","","{}");
        repo.registrationProgress(shop,item,item.gtin(),"WAITING_REMOTE","LOCAL_ACTIVATION","","{\"marketplaceVerified\":true}");
        var snapshot=GtinRunStatusSnapshot.read(GtinRunStatusSnapshot.capture(shop,Set.of()),run);
        assertTrue(snapshot.settled());assertEquals(1,snapshot.result().progress().succeeded());assertEquals(0,snapshot.result().progress().pending());
    }
    @Test void lateSnapshotRejectsChangedConnectionOwnerAndDifferentRunOwner()throws Exception{
        var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);var repo=new SyncRunRepository();var context=GtinRunStatusSnapshot.capture(shop,Set.of());
        var ozon=GtinWriteTestFixture.shop(Marketplace.OZON);String foreign=repo.begin(ozon,"znack_marketplace","READBACK");assertThrows(SecurityException.class,()->GtinRunStatusSnapshot.read(context,foreign));
        String own=repo.begin(shop,"znack_marketplace","READBACK");GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),com.vncode.app.integration.znack.ZnackModels.PRODUCTION_TRUE_API);
        assertThrows(SecurityException.class,()->GtinRunStatusSnapshot.read(context,own));
        var rotated=GtinRunStatusSnapshot.capture(shop,Set.of());
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key='rotated-fixture' WHERE id=?")){p.setInt(1,shop.getId());p.executeUpdate();}
        assertThrows(SecurityException.class,()->GtinRunStatusSnapshot.read(rotated,own));
    }
    @Test void selectedKeyMissingFromTheDurableReportIsCountedExactlyOnce()throws Exception{
        var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);var repo=new SyncRunRepository();String run=repo.begin(shop,"znack_marketplace","READBACK");
        var item=repo.add(shop,run,"100","101","","","","",GtinWriteTestFixture.GTIN,"WAITING_REMOTE","","{}");
        var context=GtinRunStatusSnapshot.capture(shop,Set.of(new ProductKey(1,Marketplace.WILDBERRIES,"100","101"),new ProductKey(1,Marketplace.WILDBERRIES,"100","missing")));
        var pending=GtinRunStatusSnapshot.read(context,run);assertEquals(2,pending.result().inventory().total());assertEquals(0,pending.result().inventory().withoutGtin());assertEquals(1,pending.result().inventory().unknown());assertEquals(0,pending.result().progress().blocked());assertEquals(1,pending.result().progress().pending());
        repo.registrationProgress(shop,item,item.gtin(),"SUCCEEDED","VERIFIED","","{\"marketplaceVerified\":true}");
        var complete=GtinRunStatusSnapshot.read(context,run);assertTrue(complete.settled());assertEquals(2,complete.result().inventory().total());assertEquals(0,complete.result().inventory().withoutGtin());assertEquals(1,complete.result().inventory().unknown());assertEquals(0,complete.result().progress().blocked());assertEquals(1,complete.result().progress().succeeded());
    }
}
