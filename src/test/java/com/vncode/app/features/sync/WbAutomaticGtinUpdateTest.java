package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WbAutomaticGtinUpdateTest {
    @TempDir Path directory;
    Shop shop;
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);
    }
    @AfterEach void close(){System.clearProperty("vncode.appdata.dir");}
    @Test void automaticPolicyIsBoundToTheExactSuccessfulPairedReadAndDoesNotContinuouslyRequestItself()throws Exception {
        assertFalse(WbAutomaticGtinUpdate.enabled(shop));WbAutomaticGtinUpdate.enableForCurrentSources(shop);
        assertTrue(WbAutomaticGtinUpdate.enabled(shop));var store=new SyncStreamStore();var before=store.existing(shop.getId(),"ZNACK");
        assertTrue(before.requested());WbAutomaticGtinUpdate.enableForCurrentSources(shop);assertEquals(before,store.existing(shop.getId(),"ZNACK"));
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);assertFalse(WbAutomaticGtinUpdate.enabled(shop));
        WbAutomaticGtinUpdate.enableForCurrentSources(shop);assertTrue(WbAutomaticGtinUpdate.enabled(shop));
        assertTrue(store.existing(shop.getId(),"ZNACK").revision()>before.revision());
    }
    @Test void changedCredentialOrFailedReadCannotReuseTheAutomaticPolicy()throws Exception {
        WbAutomaticGtinUpdate.enableForCurrentSources(shop);
        var reports=new SyncRunRepository();var failed=reports.begin(shop,RegistrationSources.KIND,"READING_MARKETPLACE");
        reports.stage(shop,failed,"FAILED","READING_MARKETPLACE","SYNC_STORAGE_BUSY");
        assertFalse(WbAutomaticGtinUpdate.enabled(shop));
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);WbAutomaticGtinUpdate.enableForCurrentSources(shop);
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key='different-fixture' WHERE id=?")){
            p.setInt(1,shop.getId());p.executeUpdate();
        }
        assertFalse(WbAutomaticGtinUpdate.enabled(shop));
    }
    @Test void wbAutomaticPolicyDoesNotGrantOzonWrites()throws Exception {
        var ozon=GtinWriteTestFixture.shop(Marketplace.OZON);
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(ozon);
        WbAutomaticGtinUpdate.enableForCurrentSources(ozon);assertFalse(WbAutomaticGtinUpdate.enabled(ozon));
    }
}
