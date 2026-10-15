package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class KizStockReplenisherTest {
    @TempDir Path path;Shop shop;Buyer buyer;KizStockReplenisher stock;
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",path.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'WB','fixture')");
            s.execute("INSERT INTO znack_products(shop_id,gtin,synced_at) VALUES(1,'"+KizWarehouseTest.GTIN+"','fixture')");
            s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,1,'"+KizWarehouseTest.GTIN+"',3,'READY','fixture','fixture')");
            s.execute("INSERT INTO kiz_codes(shop_id,order_id,gtin,raw_code,display_code,status,legal_status,created_at,updated_at) VALUES(1,1,'"+KizWarehouseTest.GTIN+"','fixture-1','fixture','AVAILABLE','IN_CIRCULATION','fixture','fixture'),(1,1,'"+KizWarehouseTest.GTIN+"','fixture-2','fixture','AVAILABLE','IN_CIRCULATION','fixture','fixture')");
        }
        shop=new Shop(1,"WB","fixture");buyer=new Buyer();stock=new KizStockReplenisher(s->buyer);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void noPurchaseWithoutSellerSettingOrWhenStockIsSufficient()throws Exception {
        assertFalse(stock.ensure(shop,Map.of(KizWarehouseTest.GTIN,5)).waiting());assertEquals(0,buyer.bought);
        new KizWarehouseRepository().autoPurchase(shop,true);assertTrue(stock.ensure(shop,Map.of(KizWarehouseTest.GTIN,2)).missing().isEmpty());assertEquals(0,buyer.bought);
    }
    @Test void purchasesOnlyMissingUnitsAndAnUnfinishedPipelinePreventsDuplicateBuys()throws Exception {
        new KizWarehouseRepository().autoPurchase(shop,true);
        var first=stock.ensure(shop,Map.of(KizWarehouseTest.GTIN,5));assertEquals(3,buyer.bought);assertEquals(1,first.enqueued());assertEquals(1,buyer.resumes);
        var again=stock.ensure(shop,Map.of(KizWarehouseTest.GTIN,5));assertEquals(0,again.enqueued());assertTrue(again.waiting());assertEquals(3,buyer.bought);
    }
    @Test void unverifiedStockRequiresVerificationRatherThanAnotherPaidOrder()throws Exception {
        new KizWarehouseRepository().autoPurchase(shop,true);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE kiz_codes SET legal_status='RECEIVED'");}
        var result=stock.ensure(shop,Map.of(KizWarehouseTest.GTIN,5));assertEquals(0,result.enqueued());assertTrue(result.waiting());assertEquals(0,buyer.bought);
    }
    @Test void twoSimultaneousPrintActionsCannotBuyTheSameDeficitTwice()throws Exception {
        new KizWarehouseRepository().autoPurchase(shop,true);var executor=Executors.newFixedThreadPool(2);
        try{var a=executor.submit(()->stock.ensure(shop,Map.of(KizWarehouseTest.GTIN,1000)));var b=executor.submit(()->stock.ensure(shop,Map.of(KizWarehouseTest.GTIN,1000)));assertEquals(1,a.get(10,TimeUnit.SECONDS).enqueued()+b.get(10,TimeUnit.SECONDS).enqueued());assertEquals(998,buyer.bought);}finally{executor.shutdownNow();}
    }
    class Buyer implements KizStockReplenisher.Purchases {
        int bought,resumes;
        public void validate(String gtin,int quantity){assertEquals(KizWarehouseTest.GTIN,gtin);assertTrue(quantity>0);}
        public void enqueue(String gtin,int quantity,String key){bought+=quantity;new ZnackRepository(new ZnackModels.ShopContext(1,"WB")).enqueuePipeline(gtin,quantity,key);}
        public void resume(){resumes++;}
    }
}
