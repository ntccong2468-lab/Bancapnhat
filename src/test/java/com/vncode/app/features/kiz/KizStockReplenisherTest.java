package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.marketplace.Marketplace;
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
            s.execute("INSERT INTO znack_products(shop_id,gtin,synced_at) VALUES(1,'"+KizWarehouseTest.GTIN+"','2026-01-01T00:00:00Z')");
            s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,1,'"+KizWarehouseTest.GTIN+"',3,'READY','fixture','fixture')");
            s.execute("INSERT INTO kiz_codes(shop_id,order_id,gtin,raw_code,display_code,status,legal_status,created_at,updated_at) VALUES(1,1,'"+KizWarehouseTest.GTIN+"','fixture-1','fixture','AVAILABLE','IN_CIRCULATION','fixture','fixture'),(1,1,'"+KizWarehouseTest.GTIN+"','fixture-2','fixture','AVAILABLE','IN_CIRCULATION','fixture','fixture')");
        }
        shop=new Shop(1,"WB","fixture");buyer=new Buyer();stock=new KizStockReplenisher(s->buyer);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void explicitPurchaseSourceIgnoresImportedStockAndItsPendingVerification()throws Exception {
        var file=path.resolve("imported.txt");java.nio.file.Files.writeString(file,KizWarehouseTest.mark(1));
        var imported=new KizWarehouseRepository().commit(shop,KizImportReader.read(file));assertEquals(1,imported.inserted());
        // Its RECEIVED state must not block the paid-source deficit or make it seem available.
        var result=stock.ensurePurchased(shop,Map.of(KizWarehouseTest.GTIN,3),missing->assertEquals(Map.of(KizWarehouseTest.GTIN,1),missing));
        assertEquals(1,result.enqueued());assertEquals(1,buyer.bought);assertFalse(new KizWarehouseRepository().autoPurchase(shop));
        var inventory=new ZnackGtinInventoryService();assertEquals(2,inventory.purchasedAvailableCounts(1,List.of(KizWarehouseTest.GTIN)).get(KizWarehouseTest.GTIN));
        var paid=inventory.reservePurchasedBulk(1,Map.of(KizWarehouseTest.GTIN,2)).get(KizWarehouseTest.GTIN);assertEquals(2,paid.size());assertTrue(paid.stream().allMatch(code->code.getCode().startsWith("fixture-")));
        inventory.release(1,paid);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE kiz_codes SET legal_status='IN_CIRCULATION' WHERE source_priority=0");}
        assertEquals(3,inventory.availableCounts(1,List.of(KizWarehouseTest.GTIN)).get(KizWarehouseTest.GTIN));
        assertEquals(2,inventory.purchasedAvailableCounts(1,List.of(KizWarehouseTest.GTIN)).get(KizWarehouseTest.GTIN));
        var mappings=new com.vncode.app.features.kizmapping.KizMappingRepository();
        assertEquals(3,mappings.findGtinSummaries(1).getFirst().available());
        assertEquals(2,mappings.findPurchasedGtinSummaries(1).getFirst().available());
    }
    @Test void noPurchaseWithoutSellerSettingOrWhenStockIsSufficient()throws Exception {
        assertFalse(stock.ensure(shop,Map.of(KizWarehouseTest.GTIN,5)).waiting());assertEquals(0,buyer.bought);
        new KizWarehouseRepository().autoPurchase(shop,true);assertTrue(stock.ensure(shop,Map.of(KizWarehouseTest.GTIN,2)).missing().isEmpty());assertEquals(0,buyer.bought);
    }
    @Test void shortageKeysReachRealCoordinatorValidationForWbAndOzonWithoutDuplicatePurchases()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(2,'Ozon','OZON','client','ozon-fixture')");
        }
        var keys=new HashSet<String>();
        for(var owner:List.of(shop,new Shop(2,"Ozon",Marketplace.OZON,"client","ozon-fixture"))){
            var repository=new ZnackRepository(new ZnackModels.ShopContext(owner.getId(),owner.getName()));
            repository.upsertProducts(List.of(new ZnackModels.Product(KizWarehouseTest.GTIN,"Fixture",null,null,null,null,null)));
            var prerequisitesReached=new IllegalStateException("fixture-prerequisites-reached");
            var coordinator=new ZnackPurchaseCoordinator(repository,null,null,null){
                @Override public void validatePrerequisites(ZnackModels.Settings settings,String gtin,int quantity){throw prerequisitesReached;}
            };
            var strict=new KizStockReplenisher(ignored->new KizStockReplenisher.Purchases(){
                public void validate(String gtin,int quantity){}
                public void enqueue(String gtin,int quantity,String key){
                    // Exercise the actual consumer contract and stop before signing or a remote purchase.
                    assertSame(prerequisitesReached,assertThrows(IllegalStateException.class,
                            ()->coordinator.enqueue(ZnackModels.Settings.empty(),gtin,quantity,key)));
                    assertTrue(keys.add(key),"Each paid deficit needs its own request identity");
                    repository.enqueuePipeline(gtin,quantity,key);
                }
                public void resume(){}
            });
            assertEquals(1,strict.ensurePurchased(owner,Map.of(KizWarehouseTest.GTIN,5),missing->{}).enqueued());
            var again=strict.ensurePurchased(owner,Map.of(KizWarehouseTest.GTIN,5),missing->fail("Pending purchase must not buy again"));
            assertEquals(0,again.enqueued());assertTrue(again.waiting());
            assertEquals(1,repository.findActivePipelines().size());
        }
        assertEquals(2,keys.size());
    }
    @Test void purchasesOnlyMissingUnitsAndAnUnfinishedPipelinePreventsDuplicateBuys()throws Exception {
        new KizWarehouseRepository().autoPurchase(shop,true);
        var first=stock.ensure(shop,Map.of(KizWarehouseTest.GTIN,5));assertEquals(3,buyer.bought);assertEquals(1,first.enqueued());assertEquals(1,buyer.resumes);
        var again=stock.ensure(shop,Map.of(KizWarehouseTest.GTIN,5));assertEquals(0,again.enqueued());assertTrue(again.waiting());assertEquals(3,buyer.bought);
        assertEquals(2,buyer.resumes,"Printing again must resume the existing purchase, not merely report pending forever");
    }
    @Test void readyPurchasedStockWinsOverAnOlderPendingPurchase()throws Exception {
        new ZnackRepository(new ZnackModels.ShopContext(1,"WB")).enqueuePipeline(KizWarehouseTest.GTIN,200,UUID.randomUUID().toString());
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO kiz_codes(shop_id,order_id,gtin,raw_code,display_code,status,legal_status,created_at,updated_at) VALUES(1,1,?,?,'fixture','AVAILABLE','IN_CIRCULATION','fixture','fixture')")){
            for(int i=0;i<175;i++){p.setString(1,KizWarehouseTest.GTIN);p.setString(2,"ready-"+i);p.addBatch();}p.executeBatch();
        }
        for(var market:List.of(Marketplace.WILDBERRIES,Marketplace.OZON)){
            try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET marketplace=?,client_id=? WHERE id=1")){p.setString(1,market.name());p.setString(2,market==Marketplace.OZON?"fixture-client":null);p.executeUpdate();}
            var owner=new Shop(1,"Fixture",market,"fixture-client","fixture");
            var ready=stock.ensurePurchased(owner,Map.of(KizWarehouseTest.GTIN,177),missing->fail("Ready stock needs no purchase proof"));
            assertFalse(ready.waiting());assertEquals(0,ready.enqueued());assertTrue(ready.missing().isEmpty());
            var inventory=new ZnackGtinInventoryService();var reserved=inventory.reservePurchasedBulk(1,Map.of(KizWarehouseTest.GTIN,177)).get(KizWarehouseTest.GTIN);
            assertEquals(177,reserved.size());inventory.release(1,reserved);
        }
        assertEquals(0,buyer.bought);assertEquals(0,buyer.resumes);
    }
    @Test void unverifiedStockRequiresVerificationRatherThanAnotherPaidOrder()throws Exception {
        new KizWarehouseRepository().autoPurchase(shop,true);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE kiz_codes SET legal_status='RECEIVED'");}
        var result=stock.ensure(shop,Map.of(KizWarehouseTest.GTIN,5));assertEquals(0,result.enqueued());assertTrue(result.waiting());assertEquals(0,buyer.bought);
    }
    @Test void purchaseProofIsRequiredOnlyForANewPaidDeficitAndRunsBeforeTheBuyer()throws Exception{
        new KizWarehouseRepository().autoPurchase(shop,true);var checks=new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Consumer<Map<String,Integer>> rejected=missing->{checks.incrementAndGet();assertEquals(3,missing.get(KizWarehouseTest.GTIN));throw new KizAssignmentException("KIZ_GTIN_MAPPING_MISSING","","");};
        assertTrue(stock.ensure(shop,Map.of(KizWarehouseTest.GTIN,2),rejected).missing().isEmpty());assertEquals(0,checks.get());
        assertThrows(KizAssignmentException.class,()->stock.ensure(shop,Map.of(KizWarehouseTest.GTIN,5),rejected));assertEquals(1,checks.get());assertEquals(0,buyer.bought);assertEquals(0,buyer.resumes);
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
