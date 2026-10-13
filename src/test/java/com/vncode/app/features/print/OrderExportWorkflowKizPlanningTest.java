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
