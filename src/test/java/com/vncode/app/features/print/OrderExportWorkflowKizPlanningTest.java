package com.vncode.app.features.print;

import com.vncode.app.features.kiz.KizService;
import com.vncode.app.models.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.shop.ShopRepository;
import com.vncode.app.features.print.history.PrintHistoryService;
import static org.junit.jupiter.api.Assertions.*;

class OrderExportWorkflowKizPlanningTest {
    @TempDir Path directory;
    @Test void wbNativeGtinExportsBothPdfsWithRealPurchasedStockAndRecordsTheConsumedCode()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try{
            var shop=com.vncode.app.features.kiz.PendingKizFixture.create(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);String gtin=com.vncode.app.features.kiz.PendingKizFixture.GTIN;
            try(var c=Database.getConnection();var s=c.createStatement()){
                s.execute("DELETE FROM znack_card_registrations");
                s.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,11,'"+gtin+"')");
                s.execute("INSERT INTO znack_products(shop_id,gtin,card_status,synced_at) VALUES(1,'"+gtin+"','published','fixture')");
                s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,1,'"+gtin+"',1,'READY','fixture','fixture')");
                s.execute("INSERT INTO kiz_codes(shop_id,order_id,gtin,raw_code,display_code,status,legal_status,created_at,updated_at) VALUES(1,1,'"+gtin+"','01"+gtin+"21synthetic-ready','synthetic-ready','AVAILABLE','IN_CIRCULATION','fixture','fixture')");
            }
            var details=new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),List.of(),List.of());
            new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(1,shop.getMarketplace(),List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(gtin,"Fixture","Black","M",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(shop),java.time.Instant.now(),details)));
            var data=new PlanningData(1){
                public Map<Long,String> mappings(int id,List<Long> ids){return Map.of();}
                public Map<Order,String> verifiedGtins(Shop owner,List<Order> selected){return OrderExportWorkflow.verifiedPurchaseGtins(owner,selected);}
                public Map<String,List<Kiz>> reserve(int id,Map<String,Integer> quantities){return new com.vncode.app.integration.znack.ZnackGtinInventoryService().reservePurchasedBulk(id,quantities);}
            };
            var order=new Order();order.setId(1L);order.setNmId(101L);order.setBarcode("S-M");order.setName("Synthetic WB product");order.setRequiresKiz(true);order.setStickerCode("WB-SYNTHETIC-STICKER");
            var labels=directory.resolve("wb-labels.pdf");var picking=directory.resolve("wb-picking.pdf");
            var request=new OrderExportWorkflow.ExportRequest(shop,"SUP-SYNTHETIC","Synthetic",List.of(order),PrintJobOptions.defaults(),labels.toFile(),picking.toFile(),new com.vncode.app.features.kiz.KizSourceSelection(false,true));
            var result=new OrderExportWorkflow(data).export(request);assertEquals(1,result.consumedKizs().size());
            for(var path:List.of(labels,picking))try(var pdf=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(path.toFile()))){assertTrue(pdf.getNumberOfPages()>0);}
            try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT status FROM kiz_codes")){assertEquals("CONSUMED",r.getString(1));}
            assertEquals(1,new PrintHistoryService().getJobs(shop.getId()).size());assertEquals(gtin,com.vncode.app.features.kiz.KizService.extractGtin(result.exportedOrders().getFirst().getKiz()));
        }finally{System.clearProperty("vncode.appdata.dir");}
    }
    @Test void aNativeWbGtinReachesPurchasedStockWithoutAnAppRegistrationOrCategoryRule()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try{
            var shop=com.vncode.app.features.gtinsync.GtinWriteTestFixture.shop(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);String gtin=com.vncode.app.features.gtinsync.GtinWriteTestFixture.GTIN;
            try(var c=Database.getConnection();var s=c.createStatement()){
                s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,synced_at) VALUES(1,101,'Fixture','fixture')");
                s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,11,101,'M')");
                s.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,101,14177449,'Цвет','[\"Black\"]')");
                s.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,11,'WB-ORDER-BARCODE'),(1,11,'"+gtin+"')");
            }
            var details=new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),List.of(),List.of());
            var card=new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(gtin,"Fixture","Black","M",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(shop),java.time.Instant.now(),details);
            new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(1,shop.getMarketplace(),List.of(card));
            var data=new PlanningData(1){
                public Map<Long,String> mappings(int id,List<Long> ids){return Map.of();}
                public Map<Order,String> verifiedGtins(Shop owner,List<Order> selected){return OrderExportWorkflow.verifiedPurchaseGtins(owner,selected);}
                public Map<String,List<Kiz>> reserveForOrders(Shop owner,Map<String,Integer> quantities,Map<Order,String> selected,com.vncode.app.features.kiz.KizSourceSelection sources){
                    var products=selected.entrySet().stream().map(e->new com.vncode.app.features.kiz.KizPurchaseMappingProof.Product("101",e.getKey().getBarcode(),e.getValue(),"1")).toList();
                    com.vncode.app.features.kiz.KizPurchaseMappingProof.require(owner,products,quantities.keySet());return reserve(owner.getId(),quantities);
                }
            };
            var order=new Order();order.setId(1L);order.setNmId(101L);order.setBarcode("WB-ORDER-BARCODE");order.setRequiresKiz(true);
            var workflow=new OrderExportWorkflow(data);var result=workflow.assignKizCodes(List.of(order),shop,new com.vncode.app.features.kiz.KizSourceSelection(false,true));
            assertEquals(1,result.usedKizs().size());assertEquals(Map.of(gtin,1),data.quantities);assertTrue(order.getKiz().startsWith("01"+gtin));
            try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE wb_product_sizes SET tech_size='L'");}
            assertThrows(com.vncode.app.features.kiz.KizAssignmentException.class,()->workflow.assignKizCodes(List.of(order),shop,new com.vncode.app.features.kiz.KizSourceSelection(false,true)));
            assertEquals(1,data.reserveCalls,"An unrelated size must never reserve or trigger a paid purchase");
        }finally{System.clearProperty("vncode.appdata.dir");}
    }
    @Test void invalidPendingIdentityDoesNotBlockAppliedMarksNonKizOrdersOrImportedStock()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try{
            var shop=com.vncode.app.features.kiz.PendingKizFixture.create(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);
            com.vncode.app.features.kiz.PendingKizFixture.sql("UPDATE znack_card_registrations SET status='ERROR'");
            var data=new PlanningData(1){
                public Set<Long> required(int selected,List<Long> ids){return Set.of();}
                public com.vncode.app.features.kiz.KizRegistrationIdentityRepository.Snapshot pendingIdentities(Shop selected){return new com.vncode.app.features.kiz.KizRegistrationIdentityRepository().read(selected);}
                public Map<Long,String> stockCategories(int selected,List<Long> ids){return Map.of(101L,"trousers");}
                public Map<Long,List<Kiz>> reserveCategoryStock(int selected,Map<Long,Integer> quantities){return Map.of(101L,List.of(new Kiz(901,"010464558878115421imported","fixture")));}
            };
            var order=new Order();order.setId(1L);order.setNmId(101L);order.setBarcode("S-M");order.setRequiresKiz(true);
            var workflow=new OrderExportWorkflow(data);String mark="010464558878115421applied";
            data.metadata=Map.of(1L,new KizService.SgtinMetadata(true,mark));
            assertTrue(workflow.assignKizCodes(List.of(order),shop,new com.vncode.app.features.kiz.KizSourceSelection(false,true)).usedKizs().isEmpty());
            assertEquals(mark,order.getKiz());
            data.metadata=Map.of();order.setRequiresKiz(false);order.setNmId(999L);
            assertTrue(workflow.assignKizCodes(List.of(order),shop,new com.vncode.app.features.kiz.KizSourceSelection(false,true)).usedKizs().isEmpty());
            order.setRequiresKiz(true);order.setNmId(101L);
            assertEquals(1,workflow.assignKizCodes(List.of(order),shop,new com.vncode.app.features.kiz.KizSourceSelection(true,true)).usedKizs().size());
            assertTrue(order.getKiz().endsWith("imported"));assertEquals(0,data.reserveCalls);
        }finally{System.clearProperty("vncode.appdata.dir");}
    }
    @Test void exactPendingVariantSelectsReadyPurchasedStockAndRejectsAnotherNativeProduct()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try{
            var shop=com.vncode.app.features.kiz.PendingKizFixture.create(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);
            String gtin=com.vncode.app.features.kiz.PendingKizFixture.GTIN;
            com.vncode.app.features.kiz.PendingKizFixture.sql("INSERT INTO znack_products(shop_id,gtin,card_status,synced_at) VALUES(1,'"+gtin+"','pending_registration','fixture')");
            com.vncode.app.features.kiz.PendingKizFixture.sql("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,1,'"+gtin+"',2,'READY','fixture','fixture')");
            com.vncode.app.features.kiz.PendingKizFixture.sql("INSERT INTO kiz_codes(shop_id,order_id,gtin,raw_code,display_code,status,legal_status,created_at,updated_at) VALUES(1,1,'"+gtin+"','01"+gtin+"21ready','ready','AVAILABLE','IN_CIRCULATION','fixture','fixture'),(1,1,'"+gtin+"','01"+gtin+"21waiting','waiting','AVAILABLE','RECEIVED','fixture','fixture')");
            var data=new PlanningData(1){
                public com.vncode.app.features.kiz.KizRegistrationIdentityRepository.Snapshot pendingIdentities(Shop selected){return new com.vncode.app.features.kiz.KizRegistrationIdentityRepository().read(selected);}
                public Map<String,List<Kiz>> reserve(int selected,Map<String,Integer> quantities){return new com.vncode.app.integration.znack.ZnackGtinInventoryService().reservePurchasedBulk(selected,quantities);}
            };
            var order=new Order();order.setId(1L);order.setNmId(101L);order.setBarcode("S-M");order.setRequiresKiz(true);
            var workflow=new OrderExportWorkflow(data);var result=workflow.assignKizCodes(List.of(order),shop,new com.vncode.app.features.kiz.KizSourceSelection(false,true));
            assertEquals("01"+gtin+"21ready",order.getKiz());assertEquals(1,result.usedKizs().size());
            new com.vncode.app.integration.znack.ZnackGtinInventoryService().release(1,result.usedKizs());
            order.setNmId(999L);
            assertThrows(com.vncode.app.features.kiz.KizAssignmentException.class,()->workflow.assignKizCodes(List.of(order),shop,new com.vncode.app.features.kiz.KizSourceSelection(false,true)));
            assertEquals(1,new com.vncode.app.integration.znack.ZnackGtinInventoryService().purchasedAvailableCounts(1,List.of(gtin)).get(gtin));
        }finally{System.clearProperty("vncode.appdata.dir");}
    }
    @Test void sellerOnlyNeverReadsPurchaseMappingsAndReportsEveryMissingOrder()throws Exception{
        var data=new PlanningData(7){
            public Map<Long,String> mappings(int shop,List<Long> ids){fail("Seller stock must not read Catalog mappings");return Map.of();}
            public Map<String,String> activeGtinsByBarcode(int shop){fail("Seller stock must not require registered GTINs");return Map.of();}
            public Map<Long,String> stockCategories(int shop,List<Long> ids){return Map.of(1L,"trousers",2L,"trousers",3L,"trousers");}
            public Map<Long,List<Kiz>> reserveCategoryStock(int shop,Map<Long,Integer> quantities){return Map.of(1L,List.of(new Kiz(901,"0104645588781154210000000000001","category-fixture")));}
        };
        var error=assertThrows(com.vncode.app.features.kiz.KizStockShortageException.class,()->new OrderExportWorkflow(data).assignKizCodes(orders(3),new Shop(7,"Main","fixture-token"),com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY));
        assertEquals(List.of("2","3"),error.missing().stream().map(com.vncode.app.features.kiz.KizStockShortageException.Missing::order).toList());
        assertEquals(1,data.released);assertEquals(0,data.reserveCalls);
    }
    @Test void sellerOnlyUsesCategoryStockWithNoZnackOrGtinPrerequisite()throws Exception{
        var data=new PlanningData(7){
            public Map<Long,String> mappings(int shop,List<Long> ids){throw new AssertionError("Unexpected GTIN read");}
            public Map<Long,String> stockCategories(int shop,List<Long> ids){return Map.of(1L,"trousers");}
            public Map<Long,List<Kiz>> reserveCategoryStock(int shop,Map<Long,Integer> quantities){return Map.of(1L,List.of(new Kiz(901,"0104645588781154210000000000001","category-fixture")));}
        };
        var result=new OrderExportWorkflow(data).assignKizCodes(orders(1),new Shop(7,"Main","fixture-token"),com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY);
        assertEquals(1,result.usedKizs().size());assertEquals(0,data.reserveCalls);assertEquals(0,data.activeCalls);
    }
    @Test void purchaseOnlyCannotReserveImportedStockAndBothSourcesPassOnlyTheDeficit()throws Exception{
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var data=new PlanningData(7){
            public Map<Long,String> stockCategories(int shop,List<Long> ids){return Map.of(1L,"trousers",2L,"trousers");}
            public Map<Long,List<Kiz>> reserveCategoryStock(int shop,Map<Long,Integer> quantities){calls.incrementAndGet();return Map.of(1L,List.of(new Kiz(901,"0104645588781154210000000000001","category-fixture")));}
            public Map<String,List<Kiz>> reserveForOrders(Shop shop,Map<String,Integer> quantities,Map<Order,String> orders,com.vncode.app.features.kiz.KizSourceSelection sources){assertTrue(sources.purchased());return reserve(shop.getId(),quantities);}
        };
        var workflow=new OrderExportWorkflow(data);var shop=new Shop(7,"Main","fixture-token");
        workflow.assignKizCodes(orders(2),shop,new com.vncode.app.features.kiz.KizSourceSelection(false,true));
        assertEquals(0,calls.get());assertEquals(2,data.quantities.values().stream().mapToInt(Integer::intValue).sum());
        workflow.assignKizCodes(orders(2),shop,new com.vncode.app.features.kiz.KizSourceSelection(true,true));
        assertEquals(1,calls.get());assertEquals(Map.of(data.gtin(2),1),data.quantities);
    }
    @Test void noSourceStopsBeforeNetworkAndExplicitSelectionNeverReplacesAnAlreadyAttachedMark()throws Exception{
        var data=new PlanningData(7);var shop=new Shop(7,"Main","fixture-token");var workflow=new OrderExportWorkflow(data);
        assertEquals("KIZ_SOURCE_REQUIRED",assertThrows(IllegalStateException.class,()->workflow.assignKizCodes(orders(1),shop,new com.vncode.app.features.kiz.KizSourceSelection(false,false))).getMessage());
        assertEquals(0,data.metadataCalls);assertEquals(0,data.reserveCalls);
        String existing="0104645588781154210000000000001";data.metadata=Map.of(1L,new KizService.SgtinMetadata(true,existing));var orders=orders(1);
        var result=workflow.assignKizCodes(orders,shop,new com.vncode.app.features.kiz.KizSourceSelection(false,true));
        assertEquals(existing,orders.getFirst().getKiz());assertTrue(result.usedKizs().isEmpty());assertTrue(result.replaceExistingOrderIds().isEmpty());
    }
    @Test void categoryStockCanFillAnOrderWithoutAGtinMapping()throws Exception{
        var data=new PlanningData(7){
            public Map<Long,String> mappings(int shop,List<Long> ids){return Map.of();}
            public Map<Long,String> stockCategories(int shop,List<Long> ids){return Map.of(1L,"trousers");}
            public Map<Long,List<Kiz>> reserveCategoryStock(int shop,Map<Long,Integer> quantities){assertEquals(Map.of(1L,1),quantities);return Map.of(1L,List.of(new Kiz(901,"0104645588781154210000000000001","category-fixture")));}
        };
        var orders=orders(1);var result=new OrderExportWorkflow(data).assignKizCodes(orders,new Shop(7,"Main","fixture-token"));
        assertEquals(1,result.usedKizs().size());assertTrue(orders.getFirst().getKiz().startsWith("0104645588781154"));assertEquals(0,data.reserveCalls);
    }
    @Test void categoryStockIsUsedFirstAndOnlyRemainingUnitsReachExactGtinReservation()throws Exception{
        var data=new PlanningData(7){
            public Map<Long,String> stockCategories(int shop,List<Long> ids){return Map.of(1L,"trousers",2L,"trousers");}
            public Map<Long,List<Kiz>> reserveCategoryStock(int shop,Map<Long,Integer> quantities){return Map.of(1L,List.of(new Kiz(901,"0104645588781154210000000000001","category-fixture")));}
        };
        var orders=orders(2);var result=new OrderExportWorkflow(data).assignKizCodes(orders,new Shop(7,"Main","fixture-token"));
        assertEquals(2,result.usedKizs().size());assertEquals(Map.of(data.gtin(2),1),data.quantities);assertEquals(1,data.reserveCalls);
    }
    @Test void missingPurchaseGtinReleasesPreviouslyHeldCategoryStock()throws Exception{
        var data=new PlanningData(7){
            public Map<Long,String> mappings(int shop,List<Long> ids){return Map.of();}
            public Map<Long,String> stockCategories(int shop,List<Long> ids){return Map.of(1L,"trousers",2L,"trousers");}
            public Map<Long,List<Kiz>> reserveCategoryStock(int shop,Map<Long,Integer> quantities){return Map.of(1L,List.of(new Kiz(901,"0104645588781154210000000000001","category-fixture")));}
        };
        assertThrows(com.vncode.app.features.kiz.KizAssignmentException.class,()->new OrderExportWorkflow(data).assignKizCodes(orders(2),new Shop(7,"Main","fixture-token")));
        assertEquals(1,data.released);assertEquals(0,data.reserveCalls);
    }
    @Test void existingCategoryMarkIsNotReplacedJustBecauseTheProductPurchaseGtinDiffers()throws Exception{
        var data=new PlanningData(7){public Map<Long,String> stockCategories(int shop,List<Long> ids){return Map.of(1L,"trousers");}};
        String mark="0104645588781154210000000000001";data.metadata=Map.of(1L,new KizService.SgtinMetadata(true,mark));
        var orders=orders(1);var result=new OrderExportWorkflow(data).assignKizCodes(orders,new Shop(7,"Main","fixture-token"));
        assertEquals(mark,orders.getFirst().getKiz());assertTrue(result.usedKizs().isEmpty());assertTrue(result.replaceExistingOrderIds().isEmpty());assertEquals(0,data.reserveCalls);
    }
    @Test void thousandDistinctProductsReserveOnceWithOneCodePerUnitAndExactShop()throws Exception{
        var data=new PlanningData(7);var workflow=new OrderExportWorkflow(data);List<Order> orders=orders(1000);
        var result=workflow.assignKizCodes(orders,new Shop(7,"Main","fixture-token"));
        assertEquals(1,data.metadataCalls);assertEquals(1,data.mappingCalls);assertEquals(1,data.requiredCalls);assertEquals(1,data.reserveCalls);
        assertEquals(1000,data.quantities.size());assertEquals(1000,result.usedKizs().size());assertEquals(1000,orders.stream().map(Order::getKiz).distinct().count());
        assertTrue(orders.stream().allMatch(Order::isRequiresKiz));assertEquals(0,data.released);
    }
    @Test void existingMatchingMarksAreReusedAndOnlyMissingUnitsAreReserved()throws Exception{
        var data=new PlanningData(7);List<Order> orders=orders(100);String gtin=data.gtin(1);
        data.metadata=Map.of(1L,new KizService.SgtinMetadata(true,"01"+gtin+"21existing"),2L,new KizService.SgtinMetadata(true,"01"+data.gtin(999)+"21old"));
        var result=new OrderExportWorkflow(data).assignKizCodes(orders,new Shop(7,"Main","fixture-token"));
        assertEquals(99,result.usedKizs().size());assertEquals("01"+gtin+"21existing",orders.getFirst().getKiz());assertEquals(Set.of(2L),result.replaceExistingOrderIds());
        assertFalse(data.quantities.containsKey(gtin));assertEquals(1,data.reserveCalls);
    }
    @Test void duplicateOrdersFailBeforeAnyNetworkOrReservation() {
        var data=new PlanningData(7);List<Order> orders=orders(2);orders.getLast().setId(1L);
        assertThrows(IllegalArgumentException.class,()->new OrderExportWorkflow(data).assignKizCodes(orders,new Shop(7,"Main","fixture-token")));
        assertEquals(0,data.metadataCalls);assertEquals(0,data.reserveCalls);
    }
    @Test void publishedVerifiedVariantGtinOverridesLegacyRuleAndPendingVariantKeepsOldRule()throws Exception{
        var data=new PlanningData(7);data.active=Map.of("barcode-active",data.gtin(999));List<Order> orders=orders(2);
        orders.getFirst().setBarcode("barcode-active");orders.getLast().setBarcode("barcode-pending");
        new OrderExportWorkflow(data).assignKizCodes(orders,new Shop(7,"Main","fixture-token"));
        assertTrue(orders.getFirst().getKiz().startsWith("01"+data.gtin(999)));assertTrue(orders.getLast().getKiz().startsWith("01"+data.gtin(2)));
        assertFalse(data.quantities.containsKey(data.gtin(1)));assertEquals(1,data.activeCalls);
    }
    @Test void exportKeepsReusedMarkAndHistoryInOriginalShopWhileCredentialEditWaits()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());var executor=Executors.newFixedThreadPool(2);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var mutatorEntered=new CountDownLatch(1);
        try {
            Database.initDatabase();try(var c=Database.getConnection();var st=c.createStatement()){st.execute("INSERT INTO shops(id,name,api_key) VALUES(7,'Original shop','fixture-token'),(8,'Other shop','other-token')");}
            String mark="010000000000000121fixture-existing";
            var data=new PlanningData(7){
                public Map<Long,KizService.SgtinMetadata> metadata(Shop shop,List<Long> ids){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Print plan release timed out");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("FIXTURE_INTERRUPTED");}return Map.of(1L,new KizService.SgtinMetadata(true,mark));}
                public void validate(Shop shop)throws java.io.IOException{KizService.requireCurrentShop(shop.getId(),shop.getApiKey());}
            };
            var shop=new Shop(7,"Original shop","fixture-token");List<Order> orders=orders(1);orders.getFirst().setName("Fixture product");orders.getFirst().setBarcode("2040000000001");orders.getFirst().setStickerCode("WB-FIXTURE-CODE");
            Path barcode=directory.resolve("labels.pdf"),details=directory.resolve("details.pdf");
            var request=new OrderExportWorkflow.ExportRequest(shop,"SUP-1","Supply",orders,PrintJobOptions.defaults(),barcode.toFile(),details.toFile());
            var printing=executor.submit(()->new OrderExportWorkflow(data).export(request));assertTrue(entered.await(5,TimeUnit.SECONDS));
            var editing=executor.submit(()->{mutatorEntered.countDown();return new ShopRepository().update(7,new Shop(7,"Changed shop","new-fixture-token"));});
            assertTrue(mutatorEntered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->editing.get(100,TimeUnit.MILLISECONDS));release.countDown();
            var result=printing.get(20,TimeUnit.SECONDS);assertEquals(1,editing.get(5,TimeUnit.SECONDS));assertEquals(mark,result.exportedOrders().getFirst().getKiz());assertTrue(result.consumedKizs().isEmpty());
            assertTrue(Files.size(barcode)>0);assertTrue(Files.size(details)>0);var history=new PrintHistoryService();var jobs=history.getJobs(7);assertEquals(1,jobs.size());assertEquals("Original shop",jobs.getFirst().shopName());assertEquals(mark,history.getItems(result.printJobId()).getFirst().kiz());assertTrue(history.getJobs(8).isEmpty());
        } finally {release.countDown();executor.shutdownNow();System.clearProperty("vncode.appdata.dir");}
    }
    private static List<Order> orders(int quantity){List<Order> result=new ArrayList<>();for(long id=1;id<=quantity;id++){var order=new Order();order.setId(id);order.setNmId(id);order.setRequiresKiz(true);result.add(order);}return result;}
    private static class PlanningData implements OrderExportWorkflow.KizPlanningData{
        final int shop;int metadataCalls,mappingCalls,requiredCalls,reserveCalls,released,activeCalls;Map<Long,KizService.SgtinMetadata> metadata=Map.of();Map<String,Integer> quantities=Map.of();Map<String,String> active=Map.of();
        PlanningData(int shop){this.shop=shop;}
        String gtin(long id){return String.format(Locale.ROOT,"%014d",id);}
        public Map<Long,KizService.SgtinMetadata> metadata(Shop selected,List<Long> ids){assertEquals(shop,selected.getId());metadataCalls++;return metadata;}
        public Map<Long,String> mappings(int shopId,List<Long> ids){assertEquals(shop,shopId);mappingCalls++;Map<Long,String> result=new HashMap<>();ids.forEach(id->result.put(id,gtin(id)));return result;}
        public Set<Long> required(int shopId,List<Long> ids){assertEquals(shop,shopId);requiredCalls++;return Set.copyOf(ids);}
        public Map<String,String> activeGtinsByBarcode(int shopId){assertEquals(shop,shopId);activeCalls++;return active;}
        public Map<String,List<Kiz>> reserve(int shopId,Map<String,Integer> quantities){assertEquals(shop,shopId);reserveCalls++;this.quantities=Map.copyOf(quantities);Map<String,List<Kiz>> result=new LinkedHashMap<>();int key=1;for(var entry:quantities.entrySet()){List<Kiz> codes=new ArrayList<>();for(int i=0;i<entry.getValue();i++)codes.add(new Kiz(key++,"01"+entry.getKey()+"21unit-"+i,"fixture-reservation"));result.put(entry.getKey(),codes);}return result;}
        public void release(int shopId,List<Kiz> codes){assertEquals(shop,shopId);released+=codes.size();}
    }
}
