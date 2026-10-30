package com.vncode.app.integration.ozon;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonCatalogBarcodeOrderingTest {
    @TempDir Path root;
    private Shop shop;
    private String owner;
    private final OzonCatalogRepository repository=new OzonCatalogRepository();

    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        shop=GtinWriteTestFixture.shop(Marketplace.OZON);owner=ListingIdentity.fingerprint(shop);
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    private OzonProductDto product(String offer,String sku,String name,String code){
        return new OzonProductDto("101",offer,sku,name,"",false,"",List.of(code));
    }

    @Test void anOlderResponseFinishingLastCannotOverwriteANewerBarcodeOrCursor()throws Exception{
        Instant older=Instant.parse("2026-01-01T00:00:00Z"),newer=older.plusSeconds(1);
        var oldToken=com.vncode.app.features.gtinsync.MarketplaceBarcodeCache.issueOzon(shop.getId(),owner,List.of("101"));
        var newToken=com.vncode.app.features.gtinsync.MarketplaceBarcodeCache.issueOzon(shop.getId(),owner,List.of("101"));
        var newerDone=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try{
            var slow=executor.submit(()->{assertTrue(newerDone.await(5,TimeUnit.SECONDS));
                return assertThrows(OzonCatalogRepository.BarcodeSuperseded.class,
                        ()->repository.upsertPage(shop.getId(),List.of(product("old-offer","old-sku","old","OLD")),"old-cursor",false,older,owner,oldToken));});
            var fast=executor.submit(()->{try{return repository.upsertPage(shop.getId(),List.of(product("new-offer","new-sku","new","NEW")),"new-cursor",false,newer,owner,newToken);}finally{newerDone.countDown();}});
            assertEquals(1,fast.get(5,TimeUnit.SECONDS));assertEquals("OZON_CATALOG_BARCODE_SUPERSEDED",slow.get(5,TimeUnit.SECONDS).getMessage());
            var result=repository.findAll(shop.getId());assertEquals(1,result.size());assertEquals("new-offer",result.getFirst().offerId());assertEquals("new-sku",result.getFirst().sku());assertEquals(List.of("NEW"),result.getFirst().barcodes());
            assertEquals("new-cursor",new OzonSyncStateRepository().find(shop.getId()).productsLastId());
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT observed_at FROM gtin_sync_barcode_observations WHERE shop_id=? AND product_id='101' AND variant_id='new-offer'")){
                p.setInt(1,shop.getId());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(newer,Instant.parse(r.getString(1)));}
            }
        } finally {executor.shutdownNow();}
    }

    @Test void aChangedOwnerCannotCommitOldProductResponse()throws Exception{
        var token=com.vncode.app.features.gtinsync.MarketplaceBarcodeCache.issueOzon(shop.getId(),owner,List.of("101"));
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key='rotated-fixture' WHERE id=?")){p.setInt(1,shop.getId());p.executeUpdate();}
        assertThrows(SecurityException.class,()->repository.upsertPage(shop.getId(),List.of(product("offer","sku","product","123")),"cursor",false,Instant.now(),owner,token));
        assertTrue(repository.findAll(shop.getId()).isEmpty());assertTrue(new OzonSyncStateRepository().find(shop.getId()).productsLastId()==null||new OzonSyncStateRepository().find(shop.getId()).productsLastId().isBlank());
    }
}
