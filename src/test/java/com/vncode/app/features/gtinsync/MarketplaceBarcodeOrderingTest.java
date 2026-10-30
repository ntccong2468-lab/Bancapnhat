package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MarketplaceBarcodeOrderingTest {
    @TempDir Path root;
    private Shop shop;private GtinSyncModels.ProductKey key;private String owner;
    private static final Instant SAME_TIME=Instant.parse("2026-10-30T12:00:00Z");
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        key=new GtinSyncModels.ProductKey(shop.getId(),Marketplace.OZON,"501","offer-501");owner=ListingIdentity.fingerprint(shop);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,synced_at) VALUES(2,'501','offer-501','1501','fixture')");}
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void identicalClockTimestampsCannotLetAnOlderRequestOverwriteANewerOne()throws Exception{
        var older=MarketplaceBarcodeCache.issueOzon(shop.getId(),owner,List.of("501"));
        var newer=MarketplaceBarcodeCache.issueOzon(shop.getId(),owner,List.of("501"));
        assertTrue(MarketplaceBarcodeCache.observe(shop,key,List.of("newer"),SAME_TIME,newer));
        assertFalse(MarketplaceBarcodeCache.observe(shop,key,List.of("older"),SAME_TIME,older));assertEquals(List.of("newer"),codes());
    }
    @Test void anotherJvmInvalidatesAnInFlightOlderRead()throws Exception{
        var older=MarketplaceBarcodeCache.issueOzon(shop.getId(),owner,List.of("501"));
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var child=new ProcessBuilder(java,"-cp",cp,Child.class.getName(),root.toString(),SAME_TIME.toString()).redirectErrorStream(true).start();
        try{
            assertTrue(child.waitFor(20,TimeUnit.SECONDS),"Child read timed out");assertEquals(0,child.exitValue(),new String(child.getInputStream().readAllBytes()));
            assertFalse(MarketplaceBarcodeCache.observe(shop,key,List.of("older"),SAME_TIME,older));assertEquals(List.of("newer"),codes());
        }finally{if(child.isAlive())child.destroyForcibly();}
    }
    private List<String> codes()throws Exception{
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT barcode FROM ozon_product_barcodes WHERE shop_id=2 AND product_id='501' ORDER BY barcode");var r=p.executeQuery()){
            var out=new java.util.ArrayList<String>();while(r.next())out.add(r.getString(1));return out;
        }
    }
    public static class Child {
        public static void main(String[] args)throws Exception{
            System.setProperty("vncode.appdata.dir",args[0]);Database.initDatabase();
            var shop=new Shop(2,"Fixture",Marketplace.OZON,"fixture-client","fixture-token");
            var key=new GtinSyncModels.ProductKey(2,Marketplace.OZON,"501","offer-501");
            var tokens=MarketplaceBarcodeCache.issueOzon(2,ListingIdentity.fingerprint(shop),List.of("501"));
            if(!MarketplaceBarcodeCache.observe(shop,key,List.of("newer"),Instant.parse(args[1]),tokens))throw new IllegalStateException("new read was rejected");
        }
    }
}
