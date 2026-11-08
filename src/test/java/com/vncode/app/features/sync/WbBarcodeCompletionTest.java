package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WbBarcodeCompletionTest {
    @TempDir Path directory;
    final SyncRunRepository reports=new SyncRunRepository();Shop shop;String run;SyncRunRepository.Item before;
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,vendor_code,synced_at) VALUES(1,101,'Synthetic','MODEL-1','now')");
            s.execute("INSERT INTO wb_product_sizes(shop_id,nm_id,chrt_id,tech_size) VALUES(1,101,201,'XL')");
            s.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,201,'4631993764370')");
        }
        run=reports.begin(shop,"znack_marketplace","MATCHING");before=reports.add(shop,run,"101","201","","MODEL-1","Blue","XL",GtinWriteTestFixture.GTIN,"NEEDS_ACTION","GTIN_ON_ANOTHER_PRODUCT","{}");
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    Set<String> complete(Shop owner){return reports.completeUnsubmittedWbBarcodes(owner,run,List.of(new SyncRunRepository.WbBarcodeCompletion(before,"04631993764370","{\"checkResult\":\"MARKETPLACE_BARCODE_PRESENT\",\"syncResult\":\"ALREADY_UPDATED\"}")));}
    @Test void completesOnlyTheUnsubmittedDisplayRowWithoutCreatingCatalogOrChildProof(){
        assertEquals(Set.of(before.id()),complete(shop));var after=reports.items(run,0,10).getFirst();
        assertEquals("SUCCEEDED",after.state());assertEquals("04631993764370",after.gtin());assertEquals("",after.childJob());assertEquals("",after.error());
        assertTrue(new com.vncode.app.features.gtinsync.GtinSyncRepository().verifiedMappings(shop).isEmpty());
        assertEquals(Set.of(),complete(shop));
    }
    @ParameterizedTest @ValueSource(strings={"gtin","state","updated_at","child_job","product_id","variant_id"})
    void aConcurrentChangeCannotBeOverwritten(String field)throws Exception {
        String value=switch(field){case "gtin"->"00000000000024";case "state"->"SUCCEEDED";case "updated_at"->"changed-after-read";case "child_job"->"durable-child";case "product_id"->"102";default->"202";};
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE sync_run_items SET "+field+"=? WHERE id=?")){p.setString(1,value);p.setString(2,before.id());p.executeUpdate();}
        var changed=reports.items(run,0,10).getFirst();assertTrue(complete(shop).isEmpty());assertEquals(changed,reports.items(run,0,10).getFirst());
    }
    @Test void aRemovedNativeBarcodeCannotCompleteTheOldObservation()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM wb_product_size_skus WHERE shop_id=1 AND chrt_id=201");}
        assertTrue(complete(shop).isEmpty());assertEquals(before,reports.items(run,0,10).getFirst());
    }
    @Test void changedCredentialsAndAnOzonShopCannotUseTheWbCompletionMethod()throws Exception {
        var ozon=GtinWriteTestFixture.shop(Marketplace.OZON);assertThrows(SecurityException.class,()->complete(ozon));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated-synthetic' WHERE id=1");}
        assertThrows(SecurityException.class,()->complete(shop));assertEquals(before,reports.items(run,0,10).getFirst());
    }
    @Test void anUnrelatedReportKindCannotUseTheWbDisplayCompletion()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE sync_runs SET kind='registration_sources' WHERE id='"+run+"'");}
        assertThrows(SecurityException.class,()->complete(shop));assertEquals(before,reports.items(run,0,10).getFirst());
    }
}
